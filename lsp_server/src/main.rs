mod logging_io;
mod tick_listener;
mod prompts;

use std::collections::HashMap;
use std::{env, fs};
use std::future::Future;
use std::io::Write;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use dashmap::DashMap;
use logging_io::log_message;
use tick_listener::OllamaTickListener;
use serde::Deserialize;
use tokio::sync::Semaphore;
use tokio::task::JoinHandle;
use tower::Service;
use tower_lsp::jsonrpc::{Request, Response};
use tower_lsp::lsp_types::*;
use tower_lsp::{Client, ExitedError, LanguageServer, LspService, Server};
use crate::tick_listener::StreamingTickListener;

/// Minimum time between two writes of the same analysis file while a tick
/// streams. The listener sends an update for every chunk the model
/// generates, so without throttling the file would be rewritten dozens of
/// times per second while the IDE poller only picks up changes a few times
/// per second anyway. The complete document is always written once the
/// stream ends, so the file on disk is never left with a truncated
/// analysis.
const STREAM_WRITE_INTERVAL: Duration = Duration::from_millis(250);

struct ServerState {
    documents: DashMap<String, String>,
    last_uri: Mutex<Option<String>>,
    /// Caret position for a specific file, when the IDE reported one:
    /// (uri, position). Keyed by uri and only used when it matches the
    /// analyzed file, so a position recorded while viewing another file can
    /// never leak into this file's analysis, no matter in which order
    /// didOpen, didChange and the custom notifications arrive. That also
    /// means no handler needs clearing logic: a stale entry is simply
    /// ignored because its uri does not match.
    last_position: Mutex<Option<(String, Position)>>,
    /// The optional topic/goal the user set in the IDE's tool window and
    /// sent with the custom notifications. The analysis views the file
    /// through this topic when set; None means no topic. Session-wide
    /// state, not per-file: the IDE re-sends it with every custom
    /// notification, so it survives file switches and self-heals after a
    /// server restart.
    last_topic: Mutex<Option<String>>,
    /// Inputs (uri, content, cursor position, topic, attachments) of the
    /// last completed analysis, so duplicate ticks for unchanged content
    /// can be skipped. The attachments are part of the inputs because
    /// they are part of the prompt: a tick for unchanged content but
    /// changed attachments (the user checked another file in the
    /// Imports pane, or an attached file's content changed) is a new
    /// analysis, not a duplicate.
    last_analyzed: Mutex<Option<(String, String, Option<Position>, Option<String>, Vec<tick_listener::AttachedFile>)>>,
    /// Compilation/syntax errors the IDE's code analyzer reported for a
    /// file with its last trigger file, keyed by uri; the analysis prompt
    /// lists them in its Problems section. Keyed per file (unlike the
    /// session-wide topic) because errors are file-specific. Document
    /// sync (didOpen/didChange) carries no errors, so those ticks use
    /// the last reported ones; didClose drops the entry with the
    /// document.
    errors_by_uri: DashMap<String, Vec<tick_listener::CompilerError>>,
    /// The files the user attached to a file's analysis from the Imports
    /// pane, keyed by uri; the analysis prompt renders them as context
    /// after the analyzed code. Keyed per file like the errors, and with
    /// the same lifecycle: the trigger file carries them, document sync
    /// (didOpen/didChange) carries none, so those ticks use the last
    /// reported ones; didClose drops the entry with the document.
    attachments_by_uri: DashMap<String, Vec<tick_listener::AttachedFile>>,
    /// The analysis the IDE's tool window has selected in its history
    /// (an older entry), keyed by uri: the completion prompt uses it as
    /// its context instead of the server's own latest analysis, so
    /// auto-complete follows the analysis the user is looking at. Set
    /// and cleared by the selection file the IDE writes whenever its
    /// history selection changes (see watch_trigger_files); a missing
    /// entry means the live view, i.e. the server's own latest analysis
    /// (see read_analysis_for_uri). Dropped with the document on
    /// didClose, like the errors and attachments. Not dropped when a
    /// new analysis completes: the IDE writes the clear at the exact
    /// moment its panel resets to the live view, so completions stay
    /// in step with what the panel shows.
    selection_by_uri: DashMap<String, String>,
    /// Whether the IDE's Pause button is active. While paused no analysis
    /// tick runs - file opens, edits, tab switches and the reload button
    /// are all skipped - and completion requests return no items, so no
    /// LLM tokens are spent. Document sync (didOpen/didChange) keeps
    /// flowing, so the server's copy of every open file stays current for
    /// the analysis that resume triggers. Not persisted: a server restart
    /// resumes analyses.
    paused: AtomicBool,
    tick_listener: Arc<dyn StreamingTickListener>,
    client: Mutex<Option<Client>>,
    /// Ensures only one tick runs at a time so streaming updates do not
    /// interleave writes to the same analysis file.
    tick_semaphore: Arc<Semaphore>,
    /// Debounced tick task for file changes.
    pending_change_tick: tokio::sync::Mutex<Option<JoinHandle<()>>>,
    /// Cancel signal of the currently running tick. Every new trigger
    /// (file open, file change, tab switch, reload) sends on it so a
    /// stale analysis is aborted immediately instead of making the new
    /// tick wait on the semaphore for the whole analysis.
    tick_cancel: Mutex<Option<tokio::sync::watch::Sender<bool>>>,
}

impl ServerState {
    fn new(tick_listener: Arc<dyn StreamingTickListener>) -> Self {
        Self {
            documents: DashMap::new(),
            last_uri: Mutex::new(None),
            last_position: Mutex::new(None),
            last_topic: Mutex::new(None),
            last_analyzed: Mutex::new(None),
            errors_by_uri: DashMap::new(),
            attachments_by_uri: DashMap::new(),
            selection_by_uri: DashMap::new(),
            paused: AtomicBool::new(false),
            tick_listener,
            client: Mutex::new(None),
            tick_semaphore: Arc::new(Semaphore::new(1)),
            pending_change_tick: tokio::sync::Mutex::new(None),
            tick_cancel: Mutex::new(None),
        }
    }
}

struct Backend {
    client: Client,
    state: Arc<ServerState>,
}

/// Parameters for the custom `custom/didSwitchFile` and `custom/forceTick`
/// notifications: the affected document's URI and, when the IDE knows it,
/// the caret position in that document.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CustomFileParams {
    text_document: TextDocumentIdentifier,
    /// Caret position in the file. Optional: non-text editors have no
    /// caret, and older plugin builds send only the textDocument.
    #[serde(default)]
    position: Option<Position>,
    /// The optional topic/goal the analysis is viewed through. Optional:
    /// older plugin builds send only textDocument and position, and an
    /// empty or whitespace-only string clears the topic.
    #[serde(default)]
    topic: Option<String>,
}

/// Parameters for the trigger file written by the IDE when the custom
/// LSP notifications cannot be sent (the running-server registry is
/// empty because the platform never calls
/// LspServerDescriptor.createLspServerListener). The IDE writes a JSON
/// file to the hints directory; this server polls for it, reads the
/// parameters, and runs a tick. This bypasses the LSP connection
/// entirely and is the primary trigger mechanism for tab switches and
/// the reload button.
#[derive(Debug, Deserialize)]
struct TriggerFileParams {
    uri: String,
    /// Caret line in the file, when the IDE knows it.
    #[serde(default)]
    line: Option<u32>,
    /// Caret character (column) in the file, when the IDE knows it.
    #[serde(default)]
    character: Option<u32>,
    /// The optional topic/goal the analysis is viewed through.
    #[serde(default)]
    topic: Option<String>,
    /// Whether the tick bypasses the duplicate check (the reload
    /// button and the topic Apply button send force = true).
    #[serde(default)]
    force: bool,
    /// The compilation/syntax errors the IDE's code analyzer currently
    /// reports for the file, collected when the trigger file was
    /// written. Optional: older plugin builds send no errors, which
    /// clears whatever was stored for the file.
    #[serde(default)]
    errors: Option<Vec<tick_listener::CompilerError>>,
    /// The files the user checked in the Imports pane, attached to the
    /// analysis as context (name, uri, content). Optional: an empty
    /// list (nothing checked) clears whatever was stored for the
    /// file, older plugin builds send none.
    #[serde(default)]
    attachments: Option<Vec<tick_listener::AttachedFile>>,
}

/// Parameters for the pause file written by the IDE's Pause button (see
/// watch_trigger_files). Same file-based mechanism as the tick trigger
/// file: the custom LSP notifications cannot be sent because the IDE's
/// running-server registry is never populated in this build, so the IDE
/// writes a small JSON document to the hints directory and this server
/// polls for it.
#[derive(Debug, Deserialize)]
struct PauseFileParams {
    paused: bool,
}

/// Parameters for the selection file the IDE writes when the history
/// selection in its tool window changes: the analysis the auto-complete
/// should use as its context. Same file-based mechanism as the tick and
/// pause trigger files (see watch_trigger_files); a dedicated file, not a
/// field in the tick trigger, because a tick fires a re-analysis and
/// selecting a history entry must not spend LLM tokens.
#[derive(Debug, Deserialize)]
struct SelectionFileParams {
    uri: String,
    /// The selected analysis's markdown, or null when the tool window
    /// shows the live view - the server then completes from its own
    /// latest analysis again.
    #[serde(default)]
    markdown: Option<String>,
}

/// Custom service wrapper that routes standard LSP requests to the inner
/// LspService and intercepts our custom notifications.
struct CustomLspService {
    inner: LspService<Backend>,
    state: Arc<ServerState>,
}

impl Service<Request> for CustomLspService {
    type Response = Option<Response>;
    type Error = ExitedError;
    type Future = Pin<Box<dyn Future<Output = std::result::Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<std::result::Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request) -> Self::Future {
        log_message(&format!("Incoming LSP method: {}", req.method()));
        let method = req.method().to_string();
        if method == "custom/didSwitchFile" || method == "custom/forceTick" {
            // custom/didSwitchFile: the user switched editor tabs.
            // custom/forceTick: the reload button in the tool window asks
            // for a fresh analysis of the current file even though its
            // content is unchanged (run_tick skips duplicate ticks
            // otherwise), so the tick runs with force = true.
            let force = method == "custom/forceTick";
            let state = Arc::clone(&self.state);
            Box::pin(async move {
                if let Some(params) = req.params() {
                    match serde_json::from_value::<CustomFileParams>(params.clone()) {
                        Ok(params) => {
                            let uri = normalize_uri(&params.text_document.uri.to_string());
                            log_message(&format!("{} received for {}", method, uri));
                            *state.last_uri.lock().unwrap() = Some(uri.clone());
                            // Remember the caret position for this file, if
                            // the IDE sent one. Keyed by uri (see
                            // last_position) so it only ever applies to the
                            // file it was reported for; None simply means
                            // "no cursor position known for this file".
                            *state.last_position.lock().unwrap() =
                                params.position.map(|position| (uri.clone(), position));
                            // Remember the topic/goal, if the IDE sent one.
                            // Trimmed, and an empty string clears it (None),
                            // so clearing the topic field in the tool window
                            // also clears it here.
                            let topic = params
                                .topic
                                .map(|topic| topic.trim().to_string())
                                .filter(|topic| !topic.is_empty());
                            if let Some(topic) = &topic {
                                log_message(&format!("{}: topic/goal is '{}'", method, topic));
                            }
                            *state.last_topic.lock().unwrap() = topic;
                            // Abort the currently running analysis: it is
                            // for a superseded request, and the new tick
                            // would otherwise wait on the semaphore until
                            // it finishes.
                            cancel_running_tick(&state);
                            // Run the tick in the background so the LSP message
                            // loop is not blocked by LLM calls.
                            tokio::spawn(async move {
                                run_tick(&state, force).await;
                            });
                        }
                        Err(e) => {
                            log_message(&format!("{}: failed to parse params: {}", method, e));
                        }
                    }
                } else {
                    log_message(&format!("{}: no params received", method));
                }
                Ok(None)
            })
        } else {
            Box::pin(self.inner.call(req))
        }
    }
}

#[tower_lsp::async_trait]
impl LanguageServer for Backend {
    async fn initialize(&self, _: InitializeParams) -> tower_lsp::jsonrpc::Result<InitializeResult> {
        log_message("Handling initialize request");
        let result = Ok(InitializeResult {
            capabilities: ServerCapabilities {
                text_document_sync: Some(TextDocumentSyncCapability::Options(
                    TextDocumentSyncOptions {
                        open_close: Some(true),
                        change: Some(TextDocumentSyncKind::FULL),
                        will_save: None,
                        will_save_wait_until: None,
                        save: None,
                    },
                )),
                completion_provider: Some(CompletionOptions {
                    resolve_provider: Some(false),
                    trigger_characters: Some(vec![".".to_string()]),
                    work_done_progress_options: Default::default(),
                    all_commit_characters: None,
                    completion_item: None,
                }),
                // Code actions turn the analysis document's
                // "Critical for understanding" steps into applicable
                // quick fixes (Alt+Enter): each step becomes an action
                // whose edit replaces, inserts above or deletes its
                // referenced line; see the code_action handler.
                // Simple(true) advertises the capability without
                // restricting the kinds.
                code_action_provider: Some(CodeActionProviderCapability::Simple(true)),
                ..ServerCapabilities::default()
            },
            server_info: Some(ServerInfo {
                name: "greppa-lsp-server".to_string(),
                version: Some(env!("CARGO_PKG_VERSION").to_string()),
            }),
        });
        log_message("Initialize request handled");
        result
    }

    async fn initialized(&self, _: InitializedParams) {
        log_message("Greppa LSP server initialized");
        // No periodic loop. Updates are triggered by file open, file change
        // (debounced), the custom didSwitchFile notification, and the
        // custom forceTick notification sent by the reload button.
    }

    async fn shutdown(&self) -> tower_lsp::jsonrpc::Result<()> {
        Ok(())
    }

    async fn did_open(&self, params: DidOpenTextDocumentParams) {
        let uri = normalize_uri(&params.text_document.uri.to_string());
        self.state.documents.insert(uri.clone(), params.text_document.text);
        *self.state.last_uri.lock().unwrap() = Some(uri.clone());
        log_message(&format!("Current file opened: {}", uri));

        // Refresh on open disabled: only sync the document, like the
        // auto-refresh-disabled branch of did_change below. No tick is
        // scheduled, and the analysis currently streaming for another
        // file is left running: cancelling it without a superseding
        // tick would only discard the tokens it has already spent, and
        // its finished document is stored for when the user returns
        // to that file.
        if !refresh_on_open() {
            log_message("did_open: refresh on open disabled, only syncing the document");
            return;
        }

        // The analysis currently running is for a superseded file; abort it.
        cancel_running_tick(&self.state);

        let tick_state = self.state.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(500)).await;
            run_tick(&tick_state, false).await;
        });
    }

    async fn did_change(&self, params: DidChangeTextDocumentParams) {
        let uri = normalize_uri(&params.text_document.uri.to_string());
        for change in params.content_changes {
            self.state.documents.insert(uri.clone(), change.text);
        }
        *self.state.last_uri.lock().unwrap() = Some(uri.clone());
        log_message(&format!("Current file changed to: {}", uri));

        // The auto-refresh threshold: the minimum number of characters
        // that must have changed since the last completed analysis before
        // typing triggers a new one. 0 disables the automatic refresh
        // while writing entirely: the document sync above still keeps the
        // server's copy current, and analyses are then generated only by
        // the refresh triggers the IDE's settings enable (opening or
        // switching files, saving) and the explicit ones (the Analyze
        // button's forced tick, resuming a pause).
        let min_chars = auto_update_min_chars();
        if min_chars == 0 {
            log_message("did_change: auto-refresh disabled, only syncing the document");
            return;
        }

        // The content of the last completed analysis of this file, when
        // there is one: the reference the change is measured against.
        // Without a reference (this file was never analyzed, or the last
        // completed analysis was for another file) every change counts as
        // exceeding the threshold, so a file's first edits still get
        // analyzed.
        let reference = {
            let guard = self.state.last_analyzed.lock().unwrap();
            match guard.as_ref() {
                Some((last_uri, last_content, _, _, _)) if *last_uri == uri => {
                    Some(last_content.clone())
                }
                _ => None,
            }
        };
        let content = self
            .state
            .documents
            .get(&uri)
            .map(|entry| entry.clone())
            .unwrap_or_default();
        let changed = match &reference {
            Some(reference) => changed_characters(reference, &content),
            None => usize::MAX,
        };
        if changed < min_chars {
            log_message(&format!(
                "did_change: {} chars changed since the last analysis, below the minimum of {}, not re-analyzing",
                changed, min_chars
            ));
            // Too small a change to justify a new analysis. The analysis
            // currently running (if any) is left alone too: cancelling it
            // would discard an almost-current analysis, and for a file's
            // first analysis it would leave the panel stuck on the
            // "Generating analysis..." placeholder.
            return;
        }

        // The analysis currently running is for outdated content; abort it.
        cancel_running_tick(&self.state);

        // Debounced tick: wait 3 seconds after the last change before running a
        // tick, and abort any previously scheduled tick. This prevents the
        // analysis from constantly changing while typing.
        let state = self.state.clone();
        tokio::spawn(async move {
            let mut pending = state.pending_change_tick.lock().await;
            if let Some(handle) = pending.take() {
                handle.abort();
            }
            let state2 = state.clone();
            *pending = Some(tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(3)).await;
                run_tick(&state2, false).await;
            }));
        });
    }

    async fn did_close(&self, params: DidCloseTextDocumentParams) {
        let uri = normalize_uri(&params.text_document.uri.to_string());
        self.state.documents.remove(&uri);
        // The reported errors and attachments belong to the closed
        // document; a reopened file gets fresh ones with its next
        // trigger file.
        self.state.errors_by_uri.remove(&uri);
        self.state.attachments_by_uri.remove(&uri);
        // The selected-analysis override belongs to the closed document
        // too; a reopened file completes from the server's own latest
        // analysis until the tool window selects a history entry again.
        self.state.selection_by_uri.remove(&uri);
        log_message(&format!("Document closed: {}", uri));
        if let Err(e) = remove_hints_file(&uri) {
            log_message(&format!("Failed to remove analysis file for {}: {}", uri, e));
        }
    }

    async fn completion(&self, params: CompletionParams) -> tower_lsp::jsonrpc::Result<Option<CompletionResponse>> {
        let uri = normalize_uri(&params.text_document_position.text_document.uri.to_string());
        let position = params.text_document_position.position;

        *self.state.last_uri.lock().unwrap() = Some(uri.clone());
        *self.state.last_position.lock().unwrap() = Some((uri.clone(), position));

        // Paused: a completion is an LLM inference too, and the user paused
        // exactly to stop spending tokens, so no completion is produced.
        // The uri and caret recorded above still apply, so the analysis
        // that resume triggers targets the file and position the user is
        // actually at.
        if self.state.paused.load(Ordering::Relaxed) {
            log_message(&format!(
                "Completion requested for {} while paused, returning no items",
                uri
            ));
            return Ok(None);
        }

        let document_text = self
            .state
            .documents
            .get(&uri)
            .map(|entry| entry.clone())
            .unwrap_or_default();

        log_message(&format!(
            "Completion requested at {} line {} char {} (document length: {})",
            uri,
            position.line,
            position.character,
            document_text.len()
        ));

        // The analysis document to use as completion context: the history
        // entry the user selected in the tool window, when one is set for
        // this file (see selection_by_uri), otherwise the server's own
        // latest analysis - which is what the tool window's live view
        // shows. The selected entry can be an older analysis, so the
        // completions follow the analysis the user is actually looking
        // at instead of always the newest one.
        let (analysis, source) = match self.state.selection_by_uri.get(&uri) {
            Some(selected) => (Some(selected.clone()), "the selected history entry"),
            None => (read_analysis_for_uri(&uri), "the latest analysis"),
        };
        match &analysis {
            Some(analysis) => log_message(&format!(
                "Completion context: using {} ({} chars) for {}",
                source,
                analysis.chars().count(),
                uri
            )),
            None => log_message("Completion context: no analysis document yet, completing without it"),
        }

        let prompt = prompts::completion_prompt(
            &document_text,
            position.line,
            position.character,
            analysis.as_deref(),
        );

        let completion_text = match tick_listener::complete(&prompt, &document_text, position).await {
            Ok(text) => text,
            Err(e) => {
                // No completion beats a bogus one: returning no items
                // leaves the popup with the local completions only.
                log_message(&format!("LLM completion error: {}", e));
                return Ok(None);
            }
        };
        if completion_text.is_empty() {
            log_message("LLM completion returned no text, skipping completion item");
            return Ok(None);
        }

        // The popup row shows the label, so it is the first line of the
        // completion; the full (possibly multi-line) text is the
        // insertText. Showing the whole text made huge popup rows.
        let label = completion_text.lines().next().unwrap_or("").trim().to_string();

        let items = vec![CompletionItem {
            label,
            detail: Some("Greppa suggestion".to_string()),
            kind: Some(CompletionItemKind::TEXT),
            insert_text: Some(completion_text),
            // Prioritize the AI suggestion in the popup: preselect asks
            // the client to select it by default, and sortText "0" sorts
            // it to the top of the list, above the IDE's own
            // completions, because clients sort ascending by sortText.
            preselect: Some(true),
            sort_text: Some("0".to_string()),
            ..CompletionItem::default()

        }];

        Ok(Some(CompletionResponse::Array(items)))
    }

    /// Turns the analysis document's "Critical for understanding" steps
    /// into code actions, which the IDE offers in its quick fix popup
    /// (Alt+Enter). Each step becomes one action whose workspace edit
    /// applies the step's snippet to its referenced line: Replace swaps
    /// the line for the snippet, Insert places the snippet above the
    /// line, Delete removes the line (see parse_recommendation_steps
    /// and build_step_edit). The steps are parsed from the analysis
    /// file already on disk, so answering spends no LLM tokens - which
    /// is also why the pause does not suppress code actions, unlike
    /// completions.
    ///
    /// The Automatable group is deliberately not offered: its fenced
    /// blocks hold AI agent prompts, not code, and applying them as
    /// edits would paste a prompt into the file.
    async fn code_action(
        &self,
        params: CodeActionParams,
    ) -> tower_lsp::jsonrpc::Result<Option<CodeActionResponse>> {
        let uri = normalize_uri(&params.text_document.uri.to_string());
        log_message(&format!("Code actions requested for {}", uri));

        let analysis = match read_analysis_for_uri(&uri) {
            Some(analysis) => analysis,
            None => {
                log_message("Code actions: no analysis document for the file, returning none");
                return Ok(None);
            }
        };
        let document = match self.state.documents.get(&uri) {
            Some(entry) => entry.clone(),
            None => {
                log_message("Code actions: document not open on the server, returning none");
                return Ok(None);
            }
        };

        let steps = parse_recommendation_steps(&analysis);
        log_message(&format!(
            "Code actions: {} applicable step(s) parsed from the analysis",
            steps.len()
        ));

        // The workspace edit's changes are keyed by the document URI
        // exactly as the client sent it: the client matches the key
        // against its open documents to apply the edit, so the key must
        // be in the client's URI form, not the normalized form used for
        // the server's own document keys. It is also already the
        // url::Url type that WorkspaceEdit's changes map requires.
        let url = params.text_document.uri.clone();

        let mut actions = Vec::new();
        for step in steps {
            let edit = match build_step_edit(&document, &step) {
                Some(edit) => edit,
                None => {
                    // A stale line reference (the file changed since the
                    // analysis) or a step without code; the remaining
                    // steps are still offered.
                    log_message(&format!(
                        "Code actions: skipping the step for line {}, it cannot be applied to the current document",
                        step.line
                    ));
                    continue;
                }
            };
            let mut changes = HashMap::new();
            changes.insert(url.clone(), vec![edit]);
            actions.push(CodeActionOrCommand::CodeAction(CodeAction {
                title: format!("Greppa: {}", shorten_title(&step.description)),
                kind: Some(CodeActionKind::REFACTOR),
                edit: Some(WorkspaceEdit {
                    changes: Some(changes),
                    ..Default::default()
                }),
                ..CodeAction::default()
            }));
        }

        if actions.is_empty() {
            return Ok(None);
        }
        Ok(Some(actions))
    }
}

#[tokio::main]
async fn main() {
    log_message("[greppa-lsp] server starting");

    env::set_var("RAYON_NUM_THREADS", "12");
    env::set_var("OMP_NUM_THREADS", "12");

    // Force OpenMP (used by llama.cpp/GGUF backends) to use 12 threads
    env::set_var("OMP_NUM_THREADS", "12");

    // Print the resolved LLM configuration, so it is obvious which
    // service and model are used. Any OpenAI-compatible service works;
    // the environment variables are documented in ollama_tick_listener.
    // The API key is deliberately not logged.
    log_message(&format!("LLM base URL: {}", tick_listener::llm_base_url()));
    log_message(&format!("LLM model: {}", tick_listener::llm_model()));

    // Clear any stale analysis files left over from a previous run so the UI
    // does not show outdated content before the first tick.
    let hints_dir_path = hints_dir();
    if hints_dir_path.exists() {
        if let Err(e) = fs::remove_dir_all(&hints_dir_path) {
            log_message(&format!("Failed to clear old analysis directory: {}", e));
        } else {
            log_message(&format!("Cleared old analysis directory: {}", hints_dir_path.display()));
        }
    }

    let stdin = tokio::io::stdin();
    let stdout = tokio::io::stdout();

    let tick_listener: Arc<dyn StreamingTickListener> = Arc::new(OllamaTickListener::new());

    let state = Arc::new(ServerState::new(tick_listener));
    let state_for_service = Arc::clone(&state);

    let (service, socket) = LspService::new(move |client| {
        *state_for_service.client.lock().unwrap() = Some(client.clone());
        Backend {
            client,
            state: state_for_service.clone(),
        }
    });

    let custom_service = CustomLspService {
        inner: service,
        state: Arc::clone(&state),
    };

    // Watch for trigger files written by the IDE (tab switches, reload
    // button, topic Apply button). This is the primary trigger
    // mechanism; see watch_trigger_files.
    let trigger_state = Arc::clone(&state);
    tokio::spawn(async move {
        watch_trigger_files(trigger_state).await;
    });

    log_message("Entering LSP message loop");
    Server::new(stdin, stdout, socket).serve(custom_service).await;
}

/// Cancels the currently running tick, if any. The running tick notices
/// the signal at its next snapshot and stops; the listener task notices
/// the dropped receiver and aborts its LLM stream, so the model stops
/// generating. Ticks still waiting on the semaphore are not affected:
/// they are cheap, and the duplicate check skips them.
fn cancel_running_tick(state: &ServerState) {
    if let Some(sender) = state.tick_cancel.lock().unwrap().as_ref() {
        let _ = sender.send(true);
    }
}

/// The minimum number of characters that must have changed since the
/// last completed analysis before a document change triggers a new one;
/// 0 disables the automatic refresh while writing entirely. Configured
/// in the IDE's settings and passed as the
/// ANTIVIBER_AUTO_UPDATE_MIN_CHARS environment variable when the server
/// is launched; the default of 0 matches the IDE setting's default -
/// like every refresh trigger, the automatic refresh is off unless the
/// user opts in - and applies only when the variable is unset or
/// invalid.
fn auto_update_min_chars() -> usize {
    std::env::var("ANTIVIBER_AUTO_UPDATE_MIN_CHARS")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(0)
}

/// Whether opening a file triggers its analysis: the did_open handler
/// schedules a tick 500 ms after the open when this is on. Configured
/// in the IDE's settings and passed as the ANTIVIBER_REFRESH_ON_OPEN
/// environment variable when the server is launched; unset or any value
/// other than "true" means off - the default, matching the IDE setting's
/// default. Like every refresh trigger the open trigger is off unless
/// the user opts in, so opening files spends no tokens on a fresh
/// installation. The IDE's tab-selection listener, which also fires on
/// file opens, is gated by the same setting on the IDE side.
fn refresh_on_open() -> bool {
    std::env::var("ANTIVIBER_REFRESH_ON_OPEN")
        .map(|value| value == "true")
        .unwrap_or(false)
}

/// Returns how many characters the two strings differ by: the size of the
/// region between their common prefix and their common suffix, counted in
/// both strings. An insertion or deletion of K characters counts as K; a
/// replacement counts the removed plus the inserted characters. Typing
/// changes one spot, so the common prefix and suffix cover almost the
/// whole file and the comparison stays cheap; a real edit distance would
/// be far more expensive without making the threshold meaningfully more
/// precise.
fn changed_characters(old: &str, new: &str) -> usize {
    let old_len = old.chars().count();
    let new_len = new.chars().count();
    let prefix = old
        .chars()
        .zip(new.chars())
        .take_while(|(a, b)| a == b)
        .count();
    let suffix = old
        .chars()
        .rev()
        .zip(new.chars().rev())
        .take_while(|(a, b)| a == b)
        .count()
        .min(old_len.saturating_sub(prefix))
        .min(new_len.saturating_sub(prefix));
    (old_len - prefix - suffix) + (new_len - prefix - suffix)
}

/// Runs one analysis tick. When `force` is false, the tick is skipped if the
/// uri, content, cursor position, topic and attachments match the last
/// completed analysis; a forced tick (the reload button) always
/// re-analyzes.
async fn run_tick(state: &ServerState, force: bool) {
    // While paused no analysis runs at all, not even a forced one: the
    // pause is the user's explicit request to stop spending tokens, and
    // resuming re-analyzes the current file anyway. Checked before the
    // semaphore so a paused tick costs nothing.
    if state.paused.load(Ordering::Relaxed) {
        log_message("tick: paused, skipping analysis");
        return;
    }

    // Only one tick at a time to avoid interleaving writes to the analysis file.
    let _permit = match state.tick_semaphore.acquire().await {
        Ok(permit) => permit,
        Err(e) => {
            log_message(&format!("tick: semaphore acquisition failed: {}", e));
            return;
        }
    };


    let uri = {
        let guard = state.last_uri.lock().unwrap();
        guard.clone()
    };
    log_message(&format!("run_tick: active uri is {:?}", uri));
    if let Some(uri) = uri {
        if let Some(content) = state.documents.get(&uri).map(|entry| entry.clone()) {
            // The caret position is only used when it was reported for this
            // very file; see last_position.
            let position = {
                let guard = state.last_position.lock().unwrap();
                match guard.as_ref() {
                    Some((position_uri, position)) if *position_uri == uri => Some(*position),
                    _ => None,
                }
            };
            // The topic/goal the user set in the tool window, if any; the
            // analysis views the file through it.
            let topic = {
                let guard = state.last_topic.lock().unwrap();
                guard.clone()
            };
            // The compilation/syntax errors the IDE's code analyzer
            // reported for this file with its last trigger file, when
            // there are any; the analysis lists them in its Problems
            // section. Document sync (open, change) carries no errors,
            // so those ticks use the last reported ones.
            let errors = state
                .errors_by_uri
                .get(&uri)
                .map(|entry| entry.clone())
                .unwrap_or_default();
            // The files the user attached to this file's analysis from
            // the Imports pane, when there are any; the analysis prompt
            // renders them as context after the analyzed code. Document
            // sync (open, change) carries no attachments, so those
            // ticks use the last reported ones.
            let attachments = state
                .attachments_by_uri
                .get(&uri)
                .map(|entry| entry.clone())
                .unwrap_or_default();

            // Skip duplicate analyses. Opening or switching to a file fires
            // several triggers (did_open schedules a tick 500 ms after the
            // open, custom/didSwitchFile fires immediately, and a debounced
            // did_change can stack on top), so two ticks for the same
            // unchanged file are common. The model is nondeterministic, so
            // a re-run produces different text and needlessly rewrites the
            // analysis file, which makes the panel update again. When the
            // uri, content, position, topic and attachments match the last
            // completed analysis, there is nothing new to analyze.
            //
            // A forced tick (custom/forceTick, sent by the reload button)
            // bypasses this check: the user explicitly asked for a fresh
            // analysis even though nothing changed.
            //
            // This check must run after the semaphore is acquired: a
            // duplicate tick waiting on the semaphore only becomes skippable
            // once the first tick has recorded its result.
            if !force {
                let guard = state.last_analyzed.lock().unwrap();
                if let Some((last_uri, last_content, last_position, last_topic, last_attachments)) = guard.as_ref() {
                    if *last_uri == uri
                        && *last_content == content
                        && *last_position == position
                        && *last_topic == topic
                        && *last_attachments == attachments
                    {
                        log_message(&format!(
                            "tick: {} unchanged since the last analysis, skipping duplicate tick",
                            uri
                        ));
                        return;
                    }
                }
            }

            if let Some(topic) = topic.as_deref() {
                log_message(&format!("tick: analyzing {} through topic/goal '{}'", uri, topic));
            }

            // Start the analysis. A placeholder snapshot is returned immediately,
            // and the receiver yields the Markdown document in progressive
            // updates as the analysis streams in.
            let (initial_snapshot, mut rx) = state
                .tick_listener
                .on_tick_stream(&content, position.clone(), topic.as_deref(), &errors, attachments.clone())
                .await;

            // Write the placeholder snapshot to disk right away. It is the
            // loading indication for files without a previous analysis:
            // until the first real update arrives the panel would
            // otherwise keep showing the "No analysis yet" card,
            // which reads like the plugin is not working. For files with
            // a previous analysis the write changes nothing visually -
            // the poller forwards it, but the section merge in
            // HintsService keeps showing the old document until real
            // sections stream in (stale-while-revalidate), so the panel
            // never clears.
            if let Err(e) = write_hints_file(&uri, &initial_snapshot.markdown) {
                log_message(&format!("tick: failed to write analysis file: {}", e));
            }

            // A new analysis attempt starts: clear the error the previous
            // attempt may have reported, so the tool window's status line
            // always describes the latest attempt. A failure makes the
            // listener write a fresh error; see write_error_file.
            write_error_file("");

            // Register this tick's cancel channel so any newer trigger can
            // abort this analysis. Registered only after the duplicate
            // check above, so a skipped tick never touches the channel of
            // a real running tick.
            let (cancel_tx, mut cancel_rx) = tokio::sync::watch::channel(false);
            *state.tick_cancel.lock().unwrap() = Some(cancel_tx);

            let mut update_count = 0;
            // The most recent snapshot text, written to disk below.
            let mut last_markdown = String::new();
            let mut last_write: Option<Instant> = None;
            // Whether the most recent snapshot has not been written yet.
            let mut dirty = false;
            // Whether a newer trigger cancelled this analysis.
            let mut cancelled = false;

            // The listener streams the document top down as the model
            // generates it, so updates arrive continuously. Writing the
            // file for every update would rewrite it dozens of times per
            // second while the IDE poller only picks up changes a few times
            // per second anyway, so writes are throttled to
            // STREAM_WRITE_INTERVAL. The complete document is always
            // written once the stream ends, so the file on disk is never
            // left with a truncated analysis.
            //
            // The select also watches the cancel channel: when a newer
            // trigger arrives, this tick stops without writing or recording
            // anything, and the newer tick takes over immediately instead
            // of waiting for this (slow) analysis to finish.
            loop {
                tokio::select! {
                    changed = cancel_rx.changed() => {
                        if changed.is_ok() {
                            cancelled = true;
                            log_message(&format!(
                                "tick: {} superseded by a newer request, cancelling analysis",
                                uri
                            ));
                        }
                        break;
                    }
                    snapshot = rx.recv() => {
                        match snapshot {
                            Some(snapshot) => {
                                update_count += 1;
                                last_markdown = snapshot.markdown;
                                dirty = true;

                                if last_write.map_or(true, |t| t.elapsed() >= STREAM_WRITE_INTERVAL) {
                                    log_message(&format!(
                                        "tick: writing streaming update {} for {} ({} chars)",
                                        update_count,
                                        uri,
                                        last_markdown.chars().count()
                                    ));
                                    if let Err(e) = write_hints_file(&uri, &last_markdown) {
                                        log_message(&format!("tick: failed to write analysis file: {}", e));
                                    }
                                    last_write = Some(Instant::now());
                                    dirty = false;
                                }
                            }
                            None => break,
                        }
                    }
                }
            }

            // Unregister the cancel channel. Only the running tick may do
            // this, and the semaphore guarantees the next tick has not
            // registered yet.
            state.tick_cancel.lock().unwrap().take();

            if cancelled {
                // The partial document of a cancelled analysis is stale: do
                // not write it, and do not record the inputs as analyzed,
                // so the superseding tick re-analyzes them.
                log_message(&format!(
                    "tick: cancelled for {} after {} update(s)",
                    update_count, uri
                ));
                return;
            }

            // Final write of the complete document, skipped when the last
            // streamed update was already written.
            if dirty && !last_markdown.is_empty() {
                if let Err(e) = write_hints_file(&uri, &last_markdown) {
                    log_message(&format!("tick: failed to write analysis file: {}", e));
                }
            }

            // Remember the inputs of this completed analysis so duplicate
            // triggers for the same unchanged file are skipped until the
            // file content, cursor position, topic or attachments change
            // again.
            *state.last_analyzed.lock().unwrap() = Some((uri.clone(), content, position, topic, attachments));

            log_message(&format!(
                "tick: finished streaming {} update(s) for {}",
                update_count, uri
            ));
        } else {
            log_message(&format!("tick: no content for {}", uri));
        }
    } else {
        log_message("tick: no last uri");
    }
}

/// Watches for the files the IDE writes into the hints directory:
///
/// - tick.trigger: run a tick for its parameters. The IDE writes the
///   file when the user switches editor tabs or clicks the reload/Apply
///   button; the custom LSP notifications for the same events cannot
///   be sent because the IDE's running-server registry is never
///   populated (the platform does not call
///   LspServerDescriptor.createLspServerListener in this build). The
///   trigger file bypasses the LSP connection entirely.
///
/// - pause.trigger: pause or resume analyses and completions (the
///   Pause button in the tool window). Pausing also cancels the
///   analysis that is currently streaming, so generation stops
///   immediately instead of running to completion; resuming forces a
///   fresh analysis of the current file so the panel does not sit on
///   stale content.
///
/// - selection.trigger: set or clear the analysis the auto-complete
///   uses as its context (the history selection in the tool window).
///   The selected markdown is stored per file and the completion
///   prompt uses it instead of the server's own latest analysis; a
///   null markdown clears the override, so completions follow the
///   tool window's live view again. A dedicated file, not a field in
///   the tick trigger: a tick fires a re-analysis, and selecting a
///   history entry must not spend LLM tokens.
///
/// All files are deleted immediately after reading so each is
/// processed exactly once. The poll interval matches the IDE-side
/// hints poller (250 ms), so the perceived latency of a tab switch or
/// a pause is at most a quarter second on top of the work itself.
async fn watch_trigger_files(state: Arc<ServerState>) {
    let trigger_path = hints_dir().join("tick.trigger");
    let pause_path = hints_dir().join("pause.trigger");
    let selection_path = hints_dir().join("selection.trigger");
    log_message(&format!(
        "Watching for trigger file at {}",
        trigger_path.display()
    ));

    loop {
        tokio::time::sleep(Duration::from_millis(250)).await;

        // The pause file is processed first so a pause wins over a tick
        // trigger that arrived in the same poll.
        if let Ok(content) = fs::read_to_string(&pause_path) {
            // Delete the pause file immediately so it is processed
            // exactly once.
            let _ = fs::remove_file(&pause_path);

            match serde_json::from_str::<PauseFileParams>(&content) {
                Ok(params) => {
                    let was_paused = state.paused.swap(params.paused, Ordering::Relaxed);
                    if params.paused {
                        log_message("pause file: analyses and completions paused");
                        // Stop the analysis that is currently streaming: the
                        // cancel makes it discard its partial document, and
                        // the dropped receiver aborts the LLM stream, so the
                        // model stops generating for a panel nobody is
                        // watching.
                        cancel_running_tick(&state);
                    } else if was_paused {
                        log_message("pause file: analyses resumed, re-analyzing the current file");
                        // Forced: the file content is unchanged since the
                        // pause, but the user expects a fresh analysis now.
                        run_tick(&state, true).await;
                    }
                }
                Err(e) => {
                    log_message(&format!("pause file: failed to parse: {}", e));
                }
            }
        }

        // The selection file is processed before the tick file because
        // the tick read below continues the loop when no tick file
        // exists, which would otherwise starve the selection until a
        // tick arrives.
        if let Ok(content) = fs::read_to_string(&selection_path) {
            // Delete the selection file immediately so it is processed
            // exactly once.
            let _ = fs::remove_file(&selection_path);

            match serde_json::from_str::<SelectionFileParams>(&content) {
                Ok(params) => {
                    let uri = normalize_uri(&params.uri);
                    match params.markdown {
                        Some(markdown) if !markdown.trim().is_empty() => {
                            log_message(&format!(
                                "selection file: completions for {} use the selected analysis ({} chars)",
                                uri,
                                markdown.chars().count()
                            ));
                            state.selection_by_uri.insert(uri, markdown);
                        }
                        _ => {
                            log_message(&format!(
                                "selection file: completions for {} use the latest analysis again",
                                uri
                            ));
                            state.selection_by_uri.remove(&uri);
                        }
                    }
                }
                Err(e) => {
                    log_message(&format!("selection file: failed to parse: {}", e));
                }
            }
        }

        let content = match fs::read_to_string(&trigger_path) {
            Ok(content) => content,
            Err(_) => continue,
        };

        // Delete the trigger file immediately so it is processed exactly
        // once, even if the tick below is skipped by the duplicate check.
        let _ = fs::remove_file(&trigger_path);

        match serde_json::from_str::<TriggerFileParams>(&content) {
            Ok(params) => {
                let uri = normalize_uri(&params.uri);
                log_message(&format!(
                    "trigger file: tick for {} (force: {})",
                    uri, params.force
                ));

                // Update the state exactly like the custom notifications
                // do, so the tick analyzes the right file with the right
                // caret position and topic.
                *state.last_uri.lock().unwrap() = Some(uri.clone());
                *state.last_position.lock().unwrap() = params
                    .line
                    .map(|line| (uri.clone(), Position::new(line, params.character.unwrap_or(0))));
                let topic = params
                    .topic
                    .map(|topic| topic.trim().to_string())
                    .filter(|topic| !topic.is_empty());
                if let Some(topic) = &topic {
                    log_message(&format!("trigger file: topic/goal is '{}'", topic));
                }
                *state.last_topic.lock().unwrap() = topic;

                // Remember the compilation/syntax errors the IDE's code
                // analyzer reported for this file, so the next analysis
                // of it lists them in its Problems section - including
                // analyses triggered by document sync, which carry no
                // errors of their own. An empty list (the analyzer
                // reports no errors) clears the entry, so the analyses
                // never list errors the IDE no longer reports.
                let errors = params.errors.clone().unwrap_or_default();
                log_message(&format!(
                    "trigger file: {} compilation/syntax error(s) reported for {}",
                    errors.len(),
                    uri
                ));
                if errors.is_empty() {
                    state.errors_by_uri.remove(&uri);
                } else {
                    state.errors_by_uri.insert(uri.clone(), errors);
                }

                // Remember the files the user attached to this file's
                // analysis from the Imports pane, so the next analysis
                // of it includes them as context - including analyses
                // triggered by document sync, which carry no attachments
                // of their own. An empty list (nothing checked) clears
                // the entry.
                let attachments = params.attachments.clone().unwrap_or_default();
                log_message(&format!(
                    "trigger file: {} attachment(s) for {}",
                    attachments.len(),
                    uri
                ));
                if attachments.is_empty() {
                    state.attachments_by_uri.remove(&uri);
                } else {
                    state.attachments_by_uri.insert(uri.clone(), attachments);
                }

                // Abort the currently running analysis: it is for a
                // superseded request, and the new tick would otherwise
                // wait on the semaphore until it finishes.
                cancel_running_tick(&state);
                run_tick(&state, params.force).await;
            }
            Err(e) => {
                log_message(&format!("trigger file: failed to parse: {}", e));
            }
        }
    }
}

/// Writes the Markdown analysis document for a file. The first line is a
/// hidden URI marker that tells the IDE poller which file the analysis
/// belongs to; the rest is the document itself.
fn write_hints_file(uri: &str, markdown: &str) -> std::io::Result<()> {
    let dir = hints_dir();
    fs::create_dir_all(&dir)?;
    clean_old_hint_files(&dir);

    let file_name = format!("{}.md", sanitize_uri(uri));
    let path = dir.join(file_name);

    let content = format!("<!-- greppa-uri: {} -->\n{}", uri, markdown);

    // Write to a temporary file and rename it so the Java poller never sees a
    // partially-written or truncated file.
    let temp_path = path.with_extension("md.tmp");
    {
        let mut file = fs::File::create(&temp_path)?;
        file.write_all(content.as_bytes())?;
        file.flush()?;
    }
    fs::rename(&temp_path, &path)?;

    log_message(&format!(
        "Wrote analysis file {} ({} chars)",
        path.display(),
        markdown.chars().count()
    ));

    Ok(())
}

/// Writes the error file the IDE poller reads to surface LLM failures in
/// the tool window's status line - the same file-based mechanism as the
/// tick and pause trigger files, only in the other direction: the
/// server writes, the IDE polls. An empty message clears the displayed
/// error; run_tick writes it at the start of every analysis attempt, so
/// the status line always describes the latest attempt, and the tick
/// listener writes a fresh message whenever the LLM service fails. The
/// IDE deletes the file after reading it.
pub(crate) fn write_error_file(message: &str) {
    let dir = hints_dir();
    if let Err(e) = fs::create_dir_all(&dir) {
        log_message(&format!("Failed to create analysis directory for the error file: {}", e));
        return;
    }
    let path = dir.join("error.trigger");
    // Write to a temporary file and rename it so the IDE poller never
    // reads a partially-written message. The temporary name ends in .new,
    // not .tmp: clean_old_hint_files removes stray *.tmp files and could
    // otherwise race with this rename.
    let temp_path = dir.join("error.trigger.new");
    if let Err(e) = fs::write(&temp_path, message) {
        log_message(&format!("Failed to write the error file: {}", e));
        return;
    }
    if let Err(e) = fs::rename(&temp_path, &path) {
        log_message(&format!("Failed to rename the error file: {}", e));
        return;
    }
    if message.is_empty() {
        log_message("Cleared the error file");
    } else {
        log_message(&format!("Wrote error file: {}", message));
    }
}

fn remove_hints_file(uri: &str) -> std::io::Result<()> {
    let dir = hints_dir();
    if !dir.exists() {
        return Ok(());
    }
    let file_name = format!("{}.md", sanitize_uri(uri));
    let path = dir.join(file_name);
    if path.exists() {
        fs::remove_file(&path)?;
        log_message(&format!("removed analysis file: {}", path.display()));
    }
    Ok(())
}

/// Returns the last analysis document the server wrote for the given URI,
/// or None when the file has not been analyzed yet. The completion prompt
/// includes it as context, so completions fit what the analysis says the
/// code does and what is planned, and the code actions are derived from
/// it as well (see code_action). The analysis file's first line is the
/// hidden URI marker; the rest is the Markdown document. A partially
/// streamed analysis works as context too. The placeholder written at the
/// start of a tick (see run_tick) is a loading indication, not an
/// analysis, so it counts as "not analyzed yet".
fn read_analysis_for_uri(uri: &str) -> Option<String> {
    let path = hints_dir().join(format!("{}.md", sanitize_uri(uri)));
    let content = fs::read_to_string(path).ok()?;
    let newline = content.find('\n')?;
    let markdown = &content[newline + 1..];
    if markdown.trim().is_empty() {
        return None;
    }
    if markdown.trim() == tick_listener::REVIEWING_MESSAGE {
        return None;
    }
    Some(markdown.to_string())
}

/// What a recommendation step does to its referenced line. The analysis
/// prompt requires every step of the Critical group to state one of
/// these words right after the line number. A step that does not is
/// treated as an insertion, the one action that never destroys
/// existing code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StepKind {
    /// The snippet is the new code for the referenced line.
    Replace,
    /// The snippet is new code placed directly above the referenced
    /// line, which is kept.
    Insert,
    /// The referenced line is removed.
    Delete,
}

/// One numbered step of the analysis document's "### Critical for
/// understanding" group, reduced to what a code action needs: the
/// 1-based referenced line, the action, the first line of the
/// description (the action's title) and the code snippet.
struct RecommendationStep {
    line: u32,
    kind: StepKind,
    description: String,
    snippet: String,
}

/// Parses the "### Critical for understanding" group of an analysis
/// document into the steps that can be applied as code actions. Only
/// this group is parsed: the Automatable group's fenced blocks hold AI
/// agent prompts, which cannot be applied to the file as edits. Steps
/// without a "Line N:" reference, and steps without a snippet (except
/// deletions, which need none), are dropped - no edit can be built
/// for them.
fn parse_recommendation_steps(analysis: &str) -> Vec<RecommendationStep> {
    let mut steps = Vec::new();
    // The step whose snippet has not been read yet, and the lines of the
    // fenced block currently being collected.
    let mut current: Option<RecommendationStep> = None;
    let mut fence: Option<Vec<String>> = None;
    let mut in_critical_group = false;

    for line in analysis.lines() {
        let trimmed = line.trim();

        if let Some(buffer) = fence.as_mut() {
            // Inside a fenced block: everything up to the next ``` line is
            // snippet content. Code blocks cannot nest, so the first
            // closing fence ends the block.
            if trimmed.starts_with("```") {
                let snippet = buffer.join("\n");
                fence = None;
                if let Some(mut step) = current.take() {
                    step.snippet = snippet;
                    finish_step(step, &mut steps);
                }
            } else {
                buffer.push(line.to_string());
            }
            continue;
        }

        // A ### subheading starts or ends the Critical group.
        if trimmed.starts_with("###") {
            if let Some(step) = current.take() {
                finish_step(step, &mut steps);
            }
            in_critical_group = trimmed.eq_ignore_ascii_case("### Critical for understanding");
            continue;
        }
        // Any other ## heading ends the Recommendations section.
        if trimmed.starts_with("##") {
            if let Some(step) = current.take() {
                finish_step(step, &mut steps);
            }
            in_critical_group = false;
            continue;
        }

        if !in_critical_group {
            continue;
        }

        // A fenced block opens here and belongs to the step started by
        // the last numbered line; a fence without a pending step is stray
        // and ignored.
        if trimmed.starts_with("```") {
            if current.is_some() {
                fence = Some(Vec::new());
            }
            continue;
        }

        // A numbered line starts a step when it carries the "Line N:"
        // reference; other numbered-looking lines belong to the running
        // text and are ignored.
        if let Some(rest) = strip_step_number(trimmed) {
            if let Some((line, kind, description)) = parse_step_header(rest) {
                if let Some(step) = current.take() {
                    finish_step(step, &mut steps);
                }
                current = Some(RecommendationStep {
                    line,
                    kind,
                    description: description.to_string(),
                    snippet: String::new(),
                });
            }
        }
    }
    if let Some(step) = current.take() {
        finish_step(step, &mut steps);
    }
    steps
}

/// Keeps a finished step when an edit can be built from it: deletions
/// need no snippet, every other step does.
fn finish_step(step: RecommendationStep, steps: &mut Vec<RecommendationStep>) {
    if step.kind == StepKind::Delete || !step.snippet.trim().is_empty() {
        steps.push(step);
    }
}

/// Splits the leading "N." step number off a line, returning the rest.
fn strip_step_number(line: &str) -> Option<&str> {
    let digits = line.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return None;
    }
    let rest = &line[digits..];
    rest.strip_prefix(". ").or_else(|| rest.strip_prefix('.'))
}

/// Parses the text after a step's number, which the prompt requires to
/// start with the line reference, the item reference and the action
/// word: "Line 13: [fixes P1] Replace: Refactor the loop to cache the
/// list". The item reference names the one problem or direction
/// consideration the step addresses; the code action needs only the
/// action word and the description, so the reference is skipped here
/// and stripped from the description, keeping the action's title clean.
/// Everything is matched case-insensitively; a missing action word
/// defaults to Insert, the non-destructive action, and a missing item
/// reference (an older analysis, or a model deviation) parses
/// unchanged.
fn parse_step_header(rest: &str) -> Option<(u32, StepKind, &str)> {
    let rest = rest.trim_start();
    let bytes = rest.as_bytes();
    if bytes.len() < 5 || !bytes[..5].eq_ignore_ascii_case(b"line ") {
        return None;
    }
    let after_line = &rest[5..];
    let digits = after_line.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return None;
    }
    let line: u32 = after_line[..digits].parse().ok()?;
    let mut description = after_line[digits..]
        .trim_start_matches(|c: char| c == ':' || c.is_whitespace());

    // Skip the "[fixes ID]" item reference the prompt puts between the
    // line number and the action word. Without this, the action word
    // lookup below would see "[fixes P1] Replace:" and match nothing,
    // silently turning every Replace and Delete step into an Insert.
    if description.starts_with('[') {
        let inside = &description[1..];
        if let Some(close) = inside.find(']') {
            if inside[..close].trim_start().to_ascii_lowercase().starts_with("fixes") {
                description = inside[close + 1..].trim_start();
            }
        }
    }

    let (kind, description) = if let Some(rest) = strip_action_word(description, "Replace") {
        (StepKind::Replace, rest)
    } else if let Some(rest) = strip_action_word(description, "Insert") {
        (StepKind::Insert, rest)
    } else if let Some(rest) = strip_action_word(description, "Delete") {
        (StepKind::Delete, rest)
    } else {
        (StepKind::Insert, description)
    };
    Some((line, kind, description.trim()))
}

/// Removes a case-insensitive "Word:" prefix, returning what follows the
/// colon.
fn strip_action_word<'a>(text: &'a str, word: &str) -> Option<&'a str> {
    let prefix = format!("{}:", word.to_ascii_lowercase());
    if text.to_ascii_lowercase().starts_with(&prefix) {
        Some(&text[prefix.len()..])
    } else {
        None
    }
}

/// Builds the edit a step applies to the current document, or None when
/// the step cannot be applied: a line reference outside the document
/// (the file changed since the analysis) or a step without code. The
/// reference is validated only by existence - the analysis does not
/// record the referenced line's content, so an edit can land on
/// shifted code after edits; the IDE's undo covers that.
fn build_step_edit(document: &str, step: &RecommendationStep) -> Option<TextEdit> {
    // The analysis writes 1-based line numbers; LSP positions are 0-based.
    let index = step.line.checked_sub(1)? as usize;
    let line_count = document.lines().count();
    if index >= line_count {
        return None;
    }

    let snippet = step.snippet.trim();
    // A Delete step's block holds only the DELETE marker; a step of any
    // other kind whose snippet is exactly DELETE is read the same way.
    let is_delete = step.kind == StepKind::Delete || snippet.eq_ignore_ascii_case("DELETE");
    if !is_delete && snippet.is_empty() {
        return None;
    }

    let line = index as u32;
    let line_end = utf16_line_length(document, index) as u32;
    let (range, new_text) = if is_delete {
        // Remove the line together with its newline so no blank line
        // remains; the last line has no following newline, so only its
        // content goes.
        if index + 1 < line_count {
            (
                Range::new(Position::new(line, 0), Position::new(line + 1, 0)),
                String::new(),
            )
        } else {
            (
                Range::new(Position::new(line, 0), Position::new(line, line_end)),
                String::new(),
            )
        }
    } else if step.kind == StepKind::Replace {
        // The snippet is the line's new code; a multi-line snippet
        // replaces the single referenced line.
        (
            Range::new(Position::new(line, 0), Position::new(line, line_end)),
            snippet.to_string(),
        )
    } else {
        // Insert: an edit at the line's start whose text ends with a
        // newline places the snippet above the referenced line.
        (
            Range::new(Position::new(line, 0), Position::new(line, 0)),
            format!("{}\n", snippet),
        )
    };
    Some(TextEdit { range, new_text })
}

/// The length of the line at the 0-based index in UTF-16 code units,
/// because LSP positions count UTF-16 units, not characters or bytes.
fn utf16_line_length(document: &str, line_index: usize) -> usize {
    document
        .lines()
        .nth(line_index)
        .map(|line| line.chars().map(|c| c.len_utf16()).sum::<usize>())
        .unwrap_or(0)
}

/// Caps a step's description for the code action's title so the quick
/// fix popup stays readable; the full description remains in the
/// analysis document.
fn shorten_title(description: &str) -> String {
    const MAX_CHARS: usize = 120;
    if description.chars().count() <= MAX_CHARS {
        return description.to_string();
    }
    let cut: String = description.chars().take(MAX_CHARS - 3).collect();
    format!("{}...", cut)
}

fn clean_old_hint_files(dir: &PathBuf) {
    let max_files = 10;
    let entries: Vec<_> = match fs::read_dir(dir) {
        Ok(iter) => iter
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.path()
                    .extension()
                    .map(|ext| ext == "md" || ext == "tmp")
                    .unwrap_or(false)
            })
            .filter_map(|e| e.metadata().ok().map(|m| (e.path(), m.modified().ok())))
            .collect(),
        Err(_) => return,
    };

    // Orphaned temporary files (*.md.tmp) left behind by interrupted
    // writes are never valid analyses; remove them immediately instead of
    // letting them accumulate until the next server start. This is safe
    // because ticks are serialized by the semaphore and the temporary
    // file of the current write is only created after this cleanup runs.
    let mut markdown_files = Vec::new();
    for (path, modified) in entries {
        if path.extension().map(|ext| ext == "tmp").unwrap_or(false) {
            let _ = fs::remove_file(&path);
        } else {
            markdown_files.push((path, modified));
        }
    }

    markdown_files.sort_by(|a, b| b.1.cmp(&a.1)); // newest first
    for (path, _) in markdown_files.iter().skip(max_files) {
        let _ = fs::remove_file(path);
    }
}

fn hints_dir() -> PathBuf {
    // The IDE-side poller (HintsFilePoller) treats ANTIVIBER_HINTS_DIR as the
    // base directory; honor it here too so both sides always agree on where
    // the analysis files live.
    if let Ok(dir) = env::var("ANTIVIBER_HINTS_DIR") {
        if !dir.is_empty() {
            return PathBuf::from(dir).join("greppa-hints");
        }
    }

    #[cfg(target_os = "windows")]
    {
        std::env::var("TEMP")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("C:\\temp"))
            .join("greppa-hints")
    }
    #[cfg(not(target_os = "windows"))]
    {
        PathBuf::from("/tmp/greppa-hints")
    }
}

/// Canonicalizes file URIs so the URI styles used across the system agree
/// and always map to the same document key:
/// - IntelliJ VirtualFile URLs put the Windows drive letter in the
///   authority ("file://C:/dir/File.txt").
/// - The IDE's LSP client sends the drive letter lowercased with the colon
///   percent-encoded ("file:///c%3A/dir/File.txt").
/// - Plain LSP URIs use the path form ("file:///C:/dir/File.txt").
/// All variants are folded into the same canonical form, mirroring
/// HintsService.normalizeUri on the Java side, so document keys and lookups
/// always match no matter which side produced the URI. Without this, a
/// custom notification carrying a VirtualFile URL (didSwitchFile, forceTick)
/// would not match the document stored under the LSP client's encoded URI
/// and the tick would fail with "no content".
fn normalize_uri(uri: &str) -> String {
    if let Some(rest) = uri.strip_prefix("file://") {
        let bytes = rest.as_bytes();
        if !rest.starts_with('/')
            && bytes.len() >= 2
            && bytes[0].is_ascii_alphabetic()
            && bytes[1] == b':'
        {
            // Drive letter in the authority: "file://C:/dir/File.txt".
            let mut normalized = String::from("file:///");
            normalized.push(bytes[0].to_ascii_uppercase() as char);
            normalized.push_str(&rest[1..]);
            return normalized;
        }
        if let Some(path) = rest.strip_prefix('/') {
            // Path form: "file:///C:/dir/File.txt" or "file:///c%3A/dir/File.txt".
            let decoded = percent_decode(path);
            let decoded_bytes = decoded.as_bytes();
            if decoded_bytes.len() >= 2
                && decoded_bytes[0].is_ascii_alphabetic()
                && decoded_bytes[1] == b':'
            {
                let mut normalized = String::from("file:///");
                normalized.push(decoded_bytes[0].to_ascii_uppercase() as char);
                // Slice the String, not the byte slice: push_str needs a
                // &str, and &decoded_bytes[1..] is a &[u8]. Index 1 is a
                // valid char boundary because the guard above established
                // that byte 0 is ASCII.
                normalized.push_str(&decoded[1..]);
                return normalized;
            }
            return format!("file:///{}", decoded);
        }
    }
    uri.to_string()
}

/// Decodes %XX percent escapes, e.g. "c%3A" -> "c:". Invalid or incomplete
/// escapes are copied through unchanged. Used by normalize_uri so that
/// percent-encoded and plain URIs fold to the same document key.
fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_value(bytes[i + 1]), hex_value(bytes[i + 2])) {
                out.push(hi * 16 + lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_value(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn sanitize_uri(uri: &str) -> String {
    uri.replace(|c: char| !c.is_alphanumeric(), "_")
}
