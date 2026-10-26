use async_openai::config::OpenAIConfig;
use async_openai::types::{
    ChatCompletionRequestMessageArgs, CreateChatCompletionRequest,
    CreateChatCompletionRequestArgs, Role,
};
use async_openai::Client;
use futures::StreamExt;

use crate::prompts;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tower_lsp::lsp_types::Position;

/// Placeholder markdown shown while the analysis inference is running.
/// Written to the analysis file at the start of every tick (see run_tick
/// in main.rs), so the panel shows a loading indication instead of the
/// "No interpretation yet" card while the first analysis of a file
/// generates; files with a previous analysis keep showing it, because
/// the section merge in HintsService shows the old document until real
/// sections stream in. The underscores render as italics in the panel.
/// pub(crate) so read_analysis_for_uri in main.rs can recognize the
/// placeholder and not treat it as an analysis document for completion
/// context.
pub(crate) const REVIEWING_MESSAGE: &str = "_Generating interpretation..._";

/// Minimum time between two progress snapshots sent to the tick while the
/// analysis streams. The model writes the Markdown document top-down from
/// the first heading to the last line, so the text accumulated at any
/// moment is a valid prefix of the final document and can be rendered
/// while it grows. The interval keeps the number of snapshots (and with
/// them the disk writes, which main.rs throttles further) reasonable; the
/// complete document is always sent once at the end.
const SNAPSHOT_INTERVAL: Duration = Duration::from_millis(100);

/// How long a completion request may take before giving up. A completion
/// must feel instant; when the LLM service is busy (e.g. an analysis is
/// streaming), it is better to return no completion than to make the
/// completion popup wait for minutes.
const COMPLETION_TIMEOUT: Duration = Duration::from_secs(60);

/// Maximum number of tokens a completion may generate. The previous value
/// of 256 cut completions off after roughly 30 lines of code, because the
/// request forbade the model from generating more; 1024 leaves room for a
/// full multi-line insertion while still bounding latency and cost.
const COMPLETION_MAX_TOKENS: u16 = 1024;

/// Resolves the base URL of the LLM service's OpenAI-compatible chat
/// completions endpoint from the environment, so no provider is
/// assumed: any service that speaks the OpenAI API works - a local
/// Ollama, LM Studio, vLLM, llama.cpp's server, OpenAI itself, or
/// Anthropic's OpenAI-compatible endpoint. The default is a local
/// Ollama instance, the service the plugin originally shipped with.
pub(crate) fn llm_base_url() -> String {
    std::env::var("ANTIVIBER_LLM_BASE_URL")
        .unwrap_or_else(|_| "http://localhost:11434/v1".to_string())
}

/// Resolves the model name from the environment. The IDE's server
/// descriptor passes the model configured in the settings as
/// ANTIVIBER_LLM_MODEL, so a model set in the IDE reaches the server
/// here; the default is the Ollama tag "gemma4:31b-cloud", the model
/// the plugin's default configuration targets. Point the variable at
/// the provider's own model name (e.g. "gpt-4o-mini" for OpenAI) to
/// use a different service.
pub(crate) fn llm_model() -> String {
    std::env::var("ANTIVIBER_LLM_MODEL")
        .unwrap_or_else(|_| "gemma4:31b-cloud".to_string())
}

/// Resolves the API key sent as the bearer token. Local services such
/// as Ollama ignore it, which is why the default is a placeholder;
/// hosted services (OpenAI, Anthropic's compatible endpoint, etc.)
/// need a real key in ANTIVIBER_LLM_API_KEY.
fn llm_api_key() -> String {
    std::env::var("ANTIVIBER_LLM_API_KEY").unwrap_or_else(|_| "ollama".to_string())
}

/// Builds the client for the configured OpenAI-compatible service.
fn llm_client() -> Client<OpenAIConfig> {
    let config = OpenAIConfig::new()
        .with_api_base(llm_base_url())
        .with_api_key(llm_api_key());
    Client::with_config(config)
}

/// One compilation/syntax error the IDE's code analyzer reports for the
/// analyzed file, collected by the IDE when it wrote the trigger file
/// (see HintsFilePoller.writeTriggerFile on the Java side). The analysis
/// prompt requires the model to list every one of them in the Problems
/// section, so the document reports what the IDE actually flags instead
/// of only what the model re-derives from reading the code.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct CompilerError {
    /// The 1-based line the error is on, matching the line-number prefixes
    /// the prompt adds to the code and the editor gutter shows.
    pub line: u32,
    /// The error message as the IDE shows it in the editor and its
    /// Problems view.
    pub message: String,
}

/// One file the user attached to the analysis from the Imports pane:
/// its name and its content, capped by the IDE (see attachmentsJson on
/// the Java side). Sent in the tick trigger file and rendered by the
/// analysis prompt as context after the analyzed code.
#[derive(Debug, Clone, PartialEq, serde::Deserialize)]
pub struct AttachedFile {
    /// The file's name (for example "prompts.rs"), used in the prompt.
    pub name: String,
    /// The file's content.
    pub content: String,
}

/// Analyzes the current file on demand and streams the resulting Markdown
/// document.
#[tower_lsp::async_trait]
pub trait StreamingTickListener: Send + Sync {
    /// Returns a placeholder snapshot immediately and an mpsc receiver that
    /// yields updated snapshots as the analysis streams in. The last
    /// snapshot contains the complete Markdown document.
    ///
    /// `topic` is the optional topic/goal the user set in the IDE's tool
    /// window. When it is set, the analysis views the file through it;
    /// None means a topic-agnostic analysis.
    ///
    /// `errors` are the compilation/syntax errors the IDE's code analyzer
    /// reports for the file; the analysis lists them in its Problems
    /// section. Empty means the IDE reports none.
    ///
    /// `attachments` are the files the user checked in the Imports pane;
    /// the analysis prompt renders them as context after the analyzed
    /// code. Empty means none are attached.
    async fn on_tick_stream(
        &self,
        file_content: &str,
        cursor_position: Option<Position>,
        topic: Option<&str>,
        errors: &[CompilerError],
        attachments: Vec<AttachedFile>,
    ) -> (TickSnapshot, mpsc::Receiver<TickSnapshot>);
}

/// Snapshot emitted by the tick listener: the Markdown document produced by
/// the analysis. The server writes it to disk verbatim and the IDE renders
/// it, so no server-side parsing of the model output is needed.
#[derive(Debug, Clone)]
pub struct TickSnapshot {
    pub markdown: String,
}


/// Tick listener that asks an OpenAI-compatible LLM service (via the
/// `/v1/chat/completions` endpoint) to analyze the file in a single
/// inference and streams the Markdown document top-down as the model
/// generates it. Which service and model are used is pure configuration
/// - see llm_base_url, llm_model and llm_api_key - so the plugin does
/// not assume which one the user runs. The document is written to disk
/// verbatim, so the listener does not parse the model output at all.
pub struct OllamaTickListener {
    client: Client<OpenAIConfig>,
    model: String,
}

impl OllamaTickListener {
    pub fn new() -> Self {
        Self {
            client: llm_client(),
            model: llm_model(),
        }
    }
}

#[tower_lsp::async_trait]
impl StreamingTickListener for OllamaTickListener {
    async fn on_tick_stream(
        &self,
        file_content: &str,
        cursor_position: Option<Position>,
        topic: Option<&str>,
        errors: &[CompilerError],
        attachments: Vec<AttachedFile>,
    ) -> (TickSnapshot, mpsc::Receiver<TickSnapshot>) {
        let cursor_index = cursor_position.map(|position| {
            let index = position_to_char_index(file_content, position);
            log_message(&format!(
                "OllamaTickListener: tick at cursor index {} (line {}, char {}) in file with {} lines",
                index,
                position.line,
                position.character,
                file_content.lines().count()
            ));
            index
        });

        // Returned immediately so the caller can show a placeholder; it is
        // not written to disk.
        let placeholder = TickSnapshot {
            markdown: REVIEWING_MESSAGE.to_string(),
        };

        let (tx, rx) = mpsc::channel(128);

        let file_content_owned = file_content.to_string();
        let cursor_index_for_task = cursor_index;
        let topic_for_task = topic.map(|topic| topic.to_string());
        let errors_for_task = errors.to_vec();
        let attachments_for_task = attachments;
        let client = self.client.clone();
        let model = self.model.clone();
        tokio::spawn(async move {
            run_ollama_work(
                &file_content_owned,
                cursor_index_for_task,
                topic_for_task,
                errors_for_task,
                attachments_for_task,
                client,
                model,
                tx,
            )
            .await;
        });

        (placeholder, rx)
    }
}

/// Standalone completion helper used by main.rs. The prompt is built by
/// prompts::completion_prompt and carries the analysis document, the
/// numbered code and the <CURSOR> marker, so the model only has to
/// produce the insertion text.
///
/// The request is wrapped in a timeout: a completion must feel instant,
/// and when the LLM service is busy (e.g. an analysis is streaming) it
/// is better to return no completion than to make the completion
/// popup wait.
///
/// The model regularly wraps its answer in a Markdown code fence
/// ("```rust ... ```") even though the prompt forbids fences; inserting
/// those fence lines into the editor causes compilation errors, so
/// strip_code_fence removes them before the text is returned.
///
/// The model also regularly starts its answer by re-generating the text
/// that already ends at the caret, even though the prompt forbids
/// repeating existing code: asked to complete "margin-right: 6" it
/// returns "margin-right: 6px", which the IDE would insert as
/// "margin-right: 6margin-right: 6px". strip_repeated_prefix removes
/// that duplicated leading portion; the document and the caret
/// position say what already precedes the caret.
pub async fn complete(
    prompt: &str,
    document: &str,
    position: Position,
) -> anyhow::Result<String> {
    let client = llm_client();
    let model = llm_model();

    let request = CreateChatCompletionRequestArgs::default()
        .model(&model)
        .messages(vec![
            ChatCompletionRequestMessageArgs::default()
                .role(Role::System)
                .content("You are a code completion engine. Output only the code to insert at the marked caret position - no explanations, no markdown, no code fences.")
                .build()?,
            ChatCompletionRequestMessageArgs::default()
                .role(Role::User)
                .content(prompt)
                .build()?,
        ])
        .max_tokens(COMPLETION_MAX_TOKENS)
        .temperature(0.1_f32)
        .top_p(0.8_f32)
        .build()?;

    let response = match tokio::time::timeout(COMPLETION_TIMEOUT, client.chat().create(request)).await {
        Ok(result) => result?,
        Err(_) => {
            return Err(anyhow::anyhow!(
                "completion request timed out after {:?}",
                COMPLETION_TIMEOUT
            ));
        }
    };
    let content = response
        .choices
        .first()
        .and_then(|c| c.message.content.clone())
        .unwrap_or_default();
    let completion = strip_code_fence(&content);
    let prefix = text_before_caret(document, position);
    let stripped = strip_repeated_prefix(&prefix, &completion);
    if stripped != completion {
        log_message(&format!(
            "[completion] stripped {} leading char(s) that duplicated the text before the caret",
            completion.chars().count() - stripped.chars().count()
        ));
    }
    Ok(stripped)
}

/// Removes the Markdown code fence the model often wraps a completion in
/// despite the prompt asking for raw insertion code: a leading line that
/// starts with ``` (with or without a language tag, like "```rust") and
/// a trailing line that is exactly ``` are dropped. The code between the
/// fence lines is kept verbatim, and text without a fence is returned
/// unchanged. Inserting the fence lines themselves would cause
/// compilation errors in the editor.
fn strip_code_fence(text: &str) -> String {
    let trimmed = text.trim();
    let mut lines: Vec<&str> = trimmed.lines().collect();

    if lines
        .first()
        .map(|line| line.trim_start().starts_with("```"))
        .unwrap_or(false)
    {
        lines.remove(0);
    }
    if lines
        .last()
        .map(|line| line.trim() == "```")
        .unwrap_or(false)
    {
        lines.pop();
    }
    lines.join("\n").trim().to_string()
}

/// Returns the document text that lies before the caret: every complete
/// line above the caret line plus the caret line's content up to the
/// caret column. LSP columns count UTF-16 code units, so the column is
/// consumed in UTF-16 units; a column that would split a surrogate pair
/// includes its whole character.
fn text_before_caret(document: &str, position: Position) -> String {
    let mut prefix = String::new();
    for (index, line) in document.lines().enumerate() {
        if index as u32 == position.line {
            let mut units = 0u32;
            for character in line.chars() {
                if units >= position.character {
                    break;
                }
                units += character.len_utf16() as u32;
                prefix.push(character);
            }
            return prefix;
        }
        prefix.push_str(line);
        prefix.push('\n');
    }
    prefix
}

/// Strips the leading portion of a completion that duplicates the text
/// immediately before the caret. Completion models are trained to
/// continue a prefix and regularly re-generate the text ending at the
/// caret despite the prompt forbidding it: asked to complete
/// "margin-right: 6" the model returns "margin-right: 6px", which the
/// IDE would insert as "margin-right: 6margin-right: 6px". Whatever
/// suffix of the text before the caret the completion starts with is
/// such a repetition, so the longest overlap is removed and only the
/// genuinely new text ("px") remains. A completion that is nothing but
/// the repetition collapses to an empty string, which the caller
/// treats as "no completion".
fn strip_repeated_prefix(prefix: &str, completion: &str) -> String {
    let prefix: Vec<char> = prefix.chars().collect();
    let completion: Vec<char> = completion.chars().collect();
    let max = prefix.len().min(completion.len());
    for overlap in (1..=max).rev() {
        if prefix.ends_with(&completion[..overlap]) {
            return completion[overlap..].iter().collect();
        }
    }
    completion.iter().collect()
}

/// Runs the single analysis inference against the LLM service's
/// streaming endpoint and sends the Markdown document as it is
/// generated: every SNAPSHOT_INTERVAL the text accumulated so far goes
/// out as a snapshot, and the complete document is sent once the
/// stream ends. Because the model writes the document top-down, every
/// snapshot is a valid prefix of the final document, so the IDE can
/// render it growing in realtime.
///
/// When a snapshot send fails, the receiving tick was cancelled (its
/// receiver was dropped); the loop then aborts immediately so the
/// request stream is dropped too and the model stops generating,
/// instead of running an abandoned analysis to completion.
///
/// Whenever the LLM service fails, the error file is written as well
/// (crate::write_error_file), so the tool window's status line shows
/// the real error instead of only the generic fallback text that the
/// analysis file receives.
///
/// The `errors` are the compilation/syntax errors the IDE's code
/// analyzer reports for the file; the prompt (prompts::analysis_prompt)
/// requires the model to list them in the Problems section.
///
/// The `attachments` are the files the user checked in the Imports
/// pane; the prompt renders them as context after the analyzed code.
async fn run_ollama_work(
    file_content: &str,
    cursor_index: Option<usize>,
    topic: Option<String>,
    errors: Vec<CompilerError>,
    attachments: Vec<AttachedFile>,
    client: Client<OpenAIConfig>,
    model: String,
    tx: mpsc::Sender<TickSnapshot>,
) {
    log_message(&format!(
        "[tick llm] starting analysis for file with {} lines",
        file_content.lines().count().max(1)
    ));
    if let Some(topic) = &topic {
        log_message(&format!(
            "[tick llm] viewing the analysis through topic/goal: {}",
            topic
        ));
    }
    if !attachments.is_empty() {
        log_message(&format!(
            "[tick llm] analyzing with {} attached file(s)",
            attachments.len()
        ));
    }

    // The analysis prompt lives in prompts.rs and is shared with
    // LlmTickListener, so there is a single source of truth for the
    // Markdown document format.
    let analysis_prompt = prompts::analysis_prompt(
        cursor_index,
        file_content,
        topic.as_deref(),
        &errors,
        &attachments,
    );

    let request = match build_analysis_request(&model, &analysis_prompt) {
        Ok(request) => request,
        Err(e) => {
            log_message(&format!("[tick llm] failed to build request: {}", e));
            crate::write_error_file(&format!("LLM analysis failed: {}", e));
            send_final(&tx, "No analysis returned by the LLM service.").await;
            return;
        }
    };

    // The streaming endpoint yields the document token by token instead of
    // returning it in one piece, which is what makes the realtime updates
    // possible.
    let mut stream = match client.chat().create_stream(request).await {
        Ok(stream) => stream,
        Err(e) => {
            log_message(&format!("[tick llm] analysis failed: {}", e));
            crate::write_error_file(&format!("LLM analysis failed: {}", e));
            send_final(&tx, "No analysis returned by the LLM service.").await;
            return;
        }
    };

    let mut accumulated = String::new();
    let mut last_sent_chars = 0usize;
    let mut last_send = Instant::now();
    let mut chunk_count = 0usize;
    // Whether the receiving tick was cancelled (a snapshot send failed
    // because the receiver was dropped), so the stream must be aborted.
    let mut receiver_dropped = false;

    while let Some(result) = stream.next().await {
        let response = match result {
            Ok(response) => response,
            Err(e) => {
                // Keep whatever arrived so far; the error is visible in the
                // log and the partial document is still delivered below.
                log_message(&format!("[tick llm] stream error: {}", e));
                crate::write_error_file(&format!("LLM analysis stream failed: {}", e));
                break;
            }
        };

        for choice in response.choices {
            if let Some(content) = choice.delta.content {
                if !content.is_empty() {
                    chunk_count += 1;
                    accumulated.push_str(&content);
                }
            }
        }

        // Send the document accumulated so far at most every
        // SNAPSHOT_INTERVAL. main.rs throttles the disk writes on top of
        // this, and the complete document is always sent once the stream
        // ends, so a skipped snapshot only delays the display slightly.
        if last_send.elapsed() >= SNAPSHOT_INTERVAL {
            if tx
                .send(TickSnapshot {
                    markdown: accumulated.clone(),
                })
                .await
                .is_err()
            {
                // The receiving tick was cancelled and its receiver
                // dropped: stop pulling from the stream. Dropping it closes
                // the connection, which makes the LLM service stop
                // generating for this abandoned analysis.
                log_message("[tick llm] tick cancelled (receiver dropped), aborting LLM stream");
                receiver_dropped = true;
                break;
            }
            last_sent_chars = accumulated.chars().count();
            last_send = Instant::now();
        }
    }

    if receiver_dropped {
        // The tick that requested this analysis is gone, so its partial
        // document must not be delivered or written to disk.
        log_message("[tick llm] aborted");
        return;
    }

    // The complete document, or a fallback when the model produced nothing.
    // Sending is skipped when the last throttled snapshot already contained
    // the complete text.
    let final_markdown = if accumulated.is_empty() {
        log_message("[tick llm] stream ended without any content");
        "No analysis returned by the LLM service.".to_string()
    } else {
        // Log the raw response so that deviations from the requested
        // Markdown structure are visible in the log file.
        log_message(&format!(
            "[tick llm] raw model response ({} chars):\n{}",
            accumulated.chars().count(),
            truncate_for_log(&accumulated, 8000)
        ));
        accumulated
    };

    if final_markdown.chars().count() != last_sent_chars {
        log_message(&format!(
            "[tick llm] sending final analysis snapshot ({} chars, {} chunks)",
            final_markdown.chars().count(),
            chunk_count
        ));
        let _ = tx.send(TickSnapshot { markdown: final_markdown }).await;
    } else {
        log_message(&format!(
            "[tick llm] last snapshot already contained the complete document ({} chars, {} chunks)",
            last_sent_chars,
            chunk_count
        ));
    }

    log_message("[tick llm] finished");
}

/// Builds the chat request for the analysis inference. Kept separate so the
/// request construction (which can fail) is handled in one place.
fn build_analysis_request(
    model: &str,
    prompt: &str,
) -> anyhow::Result<CreateChatCompletionRequest> {
    let request = CreateChatCompletionRequestArgs::default()
        .model(model)
        .messages(vec![
            ChatCompletionRequestMessageArgs::default()
                .role(Role::System)
                .content("You are a precise, evidence-based code review assistant.")
                .build()?,
            ChatCompletionRequestMessageArgs::default()
                .role(Role::User)
                .content(prompt)
                .build()?,
        ])
        // The response is a full Markdown document with code snippets, so
        // it needs plenty of room.
        .max_tokens(4096u16)
        .temperature(0.1_f32)
        .top_p(0.8_f32)
        .build()?;
    Ok(request)
}

/// Sends the given markdown as the last snapshot of a stream, e.g. when the
/// request could not be started and the tick must still end with a document
/// for the panel to show.
async fn send_final(tx: &mpsc::Sender<TickSnapshot>, markdown: &str) {
    let _ = tx
        .send(TickSnapshot {
            markdown: markdown.to_string(),
        })
        .await;
}

fn position_to_char_index(content: &str, position: Position) -> usize {
    let target_line = position.line as usize;
    let target_char = position.character as usize;

    let mut current_line = 0usize;
    let mut char_index = 0usize;

    for line in content.split('\n') {
        if current_line == target_line {
            return char_index + target_char.min(line.chars().count());
        }
        char_index += line.chars().count() + 1;
        current_line += 1;
    }

    char_index
}

/// Returns the text for logging, truncated to max_len characters with a
/// marker when it is longer. The line structure is kept intact so the
/// model's output format can be inspected in the log.
fn truncate_for_log(text: &str, max_len: usize) -> String {
    let count = text.chars().count();
    if count <= max_len {
        return text.to_string();
    }
    let truncated: String = text.chars().take(max_len).collect();
    format!(
        "{}\n... [truncated: first {} of {} chars]",
        truncated, max_len, count
    )
}

fn log_message(message: &str) {
    crate::logging_io::log_message(message);
}
