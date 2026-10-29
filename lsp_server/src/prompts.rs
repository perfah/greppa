use crate::tick_listener::{AttachedFile, CompilerError};

/// The user's free-text standing instructions for the LLM, configured in
/// the IDE's settings (the guideline field) and passed to the server as
/// the ANTIVIBER_LLM_GUIDELINE environment variable when the LSP server
/// is launched. Trimmed, and None when unset or empty: no guideline.
///
/// Resolved here, where the prompts are built, so the guideline has a
/// single consumer and needs no plumbing through the tick listener. The
/// environment of a running process never changes (the IDE sets the
/// variable at server launch), so changing the guideline in the settings
/// takes effect the next time the LSP server starts.
fn guideline() -> Option<String> {
    std::env::var("ANTIVIBER_LLM_GUIDELINE")
        .ok()
        .map(|guideline| guideline.trim().to_string())
        .filter(|guideline| !guideline.is_empty())
}

/// Builds the prompt used to ask the LLM to analyze a file in a single
/// inference and produce a Markdown document.
///
/// The document must contain the following sections, in this order, each
/// introduced by a ## heading that matches a tool window section:
///
///   ## Scope (topic summary, plus answers to questions found in the code)
///   ## General understanding
///   ## Relationship to other files (how each attached file is used)
///   ## Current functionality
///   ## Problems
///   ## Recommendations
///
/// Within the sections the model may use any Markdown formatting it likes
/// (bold, italics, subheadings, lists, inline code, fenced code blocks,
/// blockquotes), which the IDE renders with proper fonts and sizes. The
/// server writes the document to disk verbatim; no server-side parsing
/// happens anymore.
///
/// Consecutive ## sections are separated by a horizontal rule (a line
/// containing only ---), which the IDE renders as a dividing line between
/// the sections.
///
/// When `topic` is set, the prompt instructs the model to view the entire
/// document through that topic: every section prioritizes what is
/// relevant to it, and a section with nothing relevant says so explicitly.
/// When it is None, the analysis is topic-agnostic.
///
/// When the user configured a guideline in the IDE's settings, it is
/// injected right after the topic as standing instructions the model must
/// follow throughout the document; see guideline.
///
/// When `errors` is non-empty it holds the compilation/syntax errors the
/// IDE's code analyzer reports for the file (the red highlights the
/// editor shows). They are injected as a list the model must report in
/// the Problems section, so the document lists what the IDE actually
/// flags instead of only what the model re-derives from reading the
/// code. Empty means the IDE reports no errors, and the prompt is
/// identical to an errors-free analysis.
///
/// When `attachments` is non-empty it holds the files the user checked in
/// the Imports pane, imported by the analyzed file. They are rendered
/// after the analyzed code as context only: the prompt forbids
/// referencing their lines or suggesting changes to them, because the
/// Line N references and the Replace/Insert/Delete steps drive the
/// IDE's code actions, which can only edit the analyzed file. The
/// Relationship to other files section describes how the analyzed file
/// uses each of them. Empty means no files are attached, and that
/// section says so.
///
/// The code is embedded with a 1-based line number prefix on every line,
/// matching the line numbers the IDE's editor gutter shows. The model must
/// use those numbers for every line reference, so the references match the
/// code exactly - without the prefixes the model had to count the lines
/// itself, which it cannot do reliably and which made the references point
/// at the wrong code. The IDE renders the references as clickable links to
/// the code.
///
/// The Recommendations section must cover both the Problems section and
/// the Suggested direction: every problem listed in the Problems
/// section must be resolved by at least one step, and at least one
/// step must work towards the Suggested direction. The prompt tells
/// the model to count its problems against its steps, so the required
/// coverage is checkable by the model itself instead of resting on a
/// vague "cover both".
///
/// The Problems and Suggested actions sections reference a single exact
/// line, never a range: the first line of the code a problem concerns, and
/// for a suggested action the line where the step's code is inserted or
/// the first existing line it replaces. The Current functionality and
/// Specific recommendations sections keep line ranges, because their
/// segments are regions of the file by design.
pub fn analysis_prompt(
    cursor_index: Option<usize>,
    file_content: &str,
    topic: Option<&str>,
    errors: &[CompilerError],
    attachments: &[AttachedFile],
) -> String {
    let total_lines = file_content.lines().count().max(1);
    // A Markdown code fence (three backticks), built without a literal fence
    // so this source file stays easy to embed and diff.
    let fence = "`".repeat(3);

    // The code with a 1-based line number prefix on every line, like the
    // IDE's editor gutter shows them. The model references these numbers
    // instead of counting lines itself, which it cannot do reliably and
    // which made the line references point at the wrong code.
    let numbered_code = file_content
        .lines()
        .enumerate()
        .map(|(index, line)| format!("{}: {}", index + 1, line))
        .collect::<Vec<_>>()
        .join("\n");

    // The optional topic/goal the user set in the tool window. When set, it
    // is injected as its own paragraph right after the intro, so the model
    // views every section through it; when absent, the prompt is identical
    // to a topic-less analysis.
    let topic_section = match topic {
        Some(topic) => format!(
            "The user has set the following topic for this review: '{topic}'. \
View the entire document through the lens of this topic: in every section, prioritize what is relevant to it. \
If a section bears no relevance to the topic, say so explicitly instead of inventing relevance.\n\n"
        ),
        None => String::new(),
    };

    // The Scope section's instructions, built per case so the prompt never
    // contains a placeholder the model could echo into the document. The
    // previous wording described both cases in one template with the topic
    // substituted into the instruction text ("Explicitly describe topic
    // NO TOPIC unless it says 'NO TOPIC'..."), and the model copied that
    // instruction text verbatim into the document instead of following it.
    // Built per case, the no-topic prompt never mentions a topic at all,
    // and the with-topic prompt names the real topic.
    let scope_section = match topic {
        Some(topic) => format!(
            "## Scope\n\
Open with one paragraph about the topic '{topic}': describe it, or answer it directly if the topic itself is a question.\n\
Follow with a bullet list (each line starts with '* ') answering every question found in the topic or in the code below - the answers, not the questions. Omit the list when there are no questions.\n"
        ),
        None => "## Scope\n\
Answer every question found in the code below in a bullet list where each line starts with '* ' - the answers, not the questions.\n\
When the code contains no questions, omit this section entirely, heading included.\n"
            .to_string(),
    };

    // The user's free-text standing instructions from the settings, when
    // configured. Injected as its own paragraph right after the topic, so
    // the model follows them in every section; when absent, the prompt is
    // identical to a guideline-free analysis.
    let guideline_section = match guideline() {
        Some(guideline) => format!(
            "The user has configured the following standing instructions for your reviews; follow them throughout the document wherever they apply:\n{guideline}\n\n"
        ),
        None => String::new(),
    };

    // The compilation/syntax errors the IDE's code analyzer reports for
    // the file, when there are any. The prompt requires the model to
    // list every one of them in the Problems section, quoting the
    // messages faithfully, so the document reports what the IDE actually
    // flags. Empty means the IDE reports no errors, and no errors block
    // is injected.
    let errors_section = if errors.is_empty() {
        String::new()
    } else {
        let error_list = errors
            .iter()
            .map(|error| format!("- Line {}: {}", error.line, error.message))
            .collect::<Vec<_>>()
            .join("\n");
        format!(
            "The IDE's code analyzer currently reports the following compilation/syntax errors in the file:\n{error_list}\n\
Every one of these errors should appear as bullets that starts with the error's line number, \
in addition to the problems you find yourself. Quote the error messages faithfully and never claim the file has no \
problems while these errors exist. \n\n"
        )
    };

    // The files the user attached from the Imports pane, rendered after
    // the analyzed code. They carry no line-number prefixes, unlike the
    // analyzed code: the Line N references and the Replace/Insert/Delete
    // steps must keep targeting the analyzed file (they drive the IDE's
    // code actions, which can only edit it), and number prefixes on the
    // attachments would invite the model to reference them.
    let attachments_section = if attachments.is_empty() {
        String::new()
    } else {
        let blocks = attachments
            .iter()
            .map(|file| {
                format!(
                    "=== ATTACHED FILE: {} ===\n{}\n=== END ATTACHED FILE ===",
                    file.name, file.content
                )
            })
            .collect::<Vec<_>>()
            .join("\n\n");
        format!(
            "\n\nThe user attached the file(s) below from the Imports pane because the analyzed file imports them. \
They are context for understanding the analyzed file only: every Line N reference and every Replace/Insert/Delete step still targets the analyzed file between BEGIN CODE and END CODE, never an attached file, and no step may change an attached file.\n\n{blocks}"
        )
    };

    let cursor_section = match cursor_index {
        Some(index) => format!(
            "The cursor is at character index {index}. In this section, mark the segment that contains the cursor by appending **(current)** to its subheading."
        ),
        None => "No cursor position is known; do not mark any segment as current.".to_string(),
    };

    format!(
        "You are an expert code reviewer. Analyze the code at the bottom and answer with a single Markdown document - no text before or after it. \
Start the document directly with its first ## heading. Never wrap the whole document, or any whole section, in a fenced code block or in BEGIN/END-style markers; only the individual code snippets and AI agent prompts described later may be fenced. \
{topic_section}\
{guideline_section}\

The document must contain all sections below, in this order, each introduced by a ## heading with exactly the text afterwards:
The text under each heading below tells you what to write; it is never content to copy into the document.
Within the sections, format the content freely with Markdown: use **bold**, *italics*, ### subheadings, bullet and numbered lists, `inline code`, blockquotes and fenced code blocks. \
Do not use # or ## headings other than the section headings listed above. \
Separate every ## section from the next with a horizontal rule: directly before every ## heading except the first one, put a line containing only --- with an empty line before and after it.

Sections:

{scope_section}
## Explanation

### Simplified
Describe what the user is writing and the purpose of the file, but explain it in a way that is easy to understand by an five/ten year old (explain like i'm 5).

### Technical
Describe what the user is writing and what the file as a whole accomplishes. Be informative, technical and elaborate.

### With increasing difficulty

Explain what the file does in gradually increasing difficulty from level 1 to level 5 using bullet points for each.
Level 1: A simple version ELI5 version that uses limited analogy. Very easy to understand.
...
Level 5: Full technical jargon with precise wording. May demand domain knowledge.

Each level should be around 3-5 sentences with the detail necessary to understand the general purpose of the file.

### Diagram

Create a compact flowchart in ASCII art showing the file's main control flow - the functions, branches and loops that
matter, not every line. But make it pedagogic / explanatory for a person who is new to what the file is about. \
Wrap it in a fenced code block: a line with {fence}, the diagram, then a closing line with {fence}. \
If the file has no control flow worth visualizing (e.g. data or configuration), write *No flowchart: nothing to
visualize.* instead.

## Relationship to other files
The user attached the file(s) listed after the code from the Imports pane, because the analyzed file imports them. \
For EVERY attached file, write one bullet that starts with the file's name and describes how the analyzed file uses it: what it imports from that file, which functions, types or values it takes from it, and the role that file plays for the analyzed file, like: - prompts.rs: Provides the prompt builders that the file calls. \
If no files are attached, write *No attached files.*

### Diagram

Create a compact flowchart in ASCII art showing how the analyzed file relates to the attached files: \
the analyzed file as one node, every attached file as its own node, and labeled arrows between them for what flows where: \
what the analyzed file imports from each file, and which functions, types or values it takes from it. \
Make it pedagogic / explanatory for a person who is new to what the file is about. \
Wrap it in a fenced code block: a line with {fence}, the diagram, then a closing line with {fence}. \
If no files are attached, write *No flowchart: nothing to visualize.* instead.

## Parts
Split the ENTIRE file into meaningful logical segments; every line of the file must belong to at least one segment, and together the segments must cover the whole file from line 1 through line {total_lines}. \
Give each segment a ### subheading that ends with its 1-based inclusive line range, like: ### Class declaration and fields (Lines 5-12). \
Under each subheading, review the segment in 2-3 sentences about potential issues, bugs or improvements.


## Suggested direction

What do you suggest the user does next on a high conceptual high level in order to accomplish their goal?

## Problems

The next two subsections have bullet lists.
Start EVERY bullet with the exact line number of the first line of the code it concerns, using the 1-based line numbers shown at the start of each code line below, like: - Line 13: Missing error handling in the loop. \
Never use a line range in this section and never invent a line number: copy it from the prefixes of the code below. \
A problem that concerns the whole file uses Line 1.
If there are no problems, write *No problems found.*

### Compilation

{errors_section}

### Logical

List the logical problems you see in the file as a whole, not per segment, one bullet per problem, explained properly. \
If there is an identified 'Suggested direction' above, you may include some problems related to it.
Be especially attentive if the user is hinting that they need help with something, like implementing a specific algorithm. \
For example: If they write 'I want to implement a peer-to-peer network using rustp2p' then 'Missing implementation for peer-to-peer network' should be considered a problem.

## Recommendations
Describe the suggested approaches for solving the problems listed in the Problems section and for advancing the Suggested direction above. \
Every step must either resolve one of the listed problems or work towards the Suggested direction; never suggest steps that pull in a different direction than the Suggested direction. \
The steps must cover both of them: EVERY problem listed in the Problems section - in both the Compilation and the Logical subsection - must be resolved by at least one step, and at least one step must work towards the Suggested direction. \
Before writing the steps, count the problems you listed in the Problems section, and verify afterwards that every single one of them is resolved by a step: one problem without a step makes the whole review incomplete. \
Here it's important to be elaborate: give each step real detail and reasoning.
This is the most important part of the review and must guide the user on what to do next. \
Every step in both groups below must start with the exact line number where the change happens: the line at which the step's code is inserted, or the FIRST line of the existing code that the step's code replaces. \
Use the 1-based line numbers shown at the start of each code line below, like: 1. Line 13: Refactor the loop to cache the list. \
Never use a line range in this section, never invent a line number (copy it from the prefixes of the code below), and never point at a line inside a suggested code snippet - the reference always points into the analyzed file. \
A step that affects the whole file uses Line 1. \
Split the steps into exactly two groups, in this order, each introduced by a ### subheading with exactly this text:

### Critical for understanding
Contains the steps that are essential for understanding the overall program - the design decisions and core logic a human should work through personally. \
Number the steps. For EVERY step, first write a detailed description of how the implementation might look: the approach, the important data structures, the control flow, and why it works. \
Then add a concrete code snippet in a fenced code block - a line with {fence} (optionally followed by the language name), then the code, then a closing line with {fence} - showing that implementation. \
Every step must state what it does to the referenced line by writing one of the words Replace, Insert or Delete right after the line number, like: 1. Line 13: Replace: Refactor the loop to cache the list. \
Replace means the snippet is the new code for the referenced line: applying the step swaps that line for the snippet. \
Insert means the snippet is new code placed directly above the referenced line, and the referenced line itself is kept. \
Delete means the referenced line is removed; the step's fenced code block contains only the word DELETE. \
Write the snippet in the language of the analyzed file. \
Every snippet must be a complete, working implementation: NEVER a placeholder, stub or skeleton, and never a comment like 'placeholder', 'TODO' or 'implement logic here' standing in for real code. \
Steps in this group must NEVER include an AI agent prompt.

### Automatable
Contains the steps that are trivial labor and do not require understanding the overall program, so the user can hand them to an AI agent directly. \
Number the steps, one short sentence per step. \
For EVERY step, add a fenced code block whose first line is 'AI agent prompt:' followed by a self-contained prompt that the user can copy-paste to an AI agent to execute the step without any other context. \
The AI agent prompt is the only code block allowed in this group; do not add normal code snippets here.

If a group has no steps, write *None.* under its subheading and add one sentence explaining that this can happen while the code does not yet have a clear direction.
If there are no problems to solve and nothing in the Suggested direction to advance, write *No suggestions.* and omit both ### subheadings.

{cursor_section}

BEGIN CODE
{numbered_code}
END CODE{attachments_section}"
    )
}

/// Builds the prompt used to ask the LLM for a code completion at the
/// caret position.
///
/// The completion is context-aware: when the file has an analysis document
/// (written by a previous analysis tick), it is included so the completion
/// fits what the code does and what the analysis says is planned. The code
/// is embedded with a 1-based line number prefix on every line (like the
/// editor gutter) and a <CURSOR> marker at the caret, so the model sees
/// the exact insertion point instead of having to count lines itself -
/// the old prompt named only the line and column numbers over the raw
/// code, and the model kept echoing code that already existed.
///
/// When the user configured a guideline in the IDE's settings, it is
/// injected as standing instructions the completion must follow, so
/// completions respect the same guidelines as analyses; see guideline.
pub fn completion_prompt(
    file_content: &str,
    line: u32,
    character: u32,
    analysis: Option<&str>,
) -> String {
    let numbered = numbered_code_with_cursor(file_content, line, character);
    // 1-based line and column, matching the numbers the editor shows.
    let gutter_line = line + 1;
    let gutter_column = character + 1;

    // The user's standing instructions from the settings, when configured,
    // so completions follow the same guidelines as analyses (e.g. style
    // preferences or constraints).
    let guideline_section = match guideline() {
        Some(guideline) => format!(
            "The user has configured the following standing instructions for all code you produce; follow them in the completion:\n{guideline}\n\n"
        ),
        None => String::new(),
    };

    // The analysis document of the file, when one exists. It is context
    // only: the model must output insertion code, not restate it.
    let analysis_section = match analysis {
        Some(analysis) => format!(
            "A code reviewer analyzed this file; its analysis follows between the ANALYSIS lines. \
Use it as context for what the code does and what changes are planned, so the completion fits the intended direction. \
Do not restate or quote the analysis; output only insertion code.\n\n\
=== ANALYSIS ===\n{analysis}\n=== END ANALYSIS ===\n\n"
        ),
        None => String::new(),
    };

    format!(
        "The user's caret is at the position marked with <CURSOR> in the code below. \
Output ONLY the text to insert at the caret: code that continues the file naturally from that exact point. \
No explanations, no markdown, no code fences, never repeat code that already exists before the caret, and never reproduce the line-number prefixes - the insertion must be new code.\n\n\
{guideline_section}\
{analysis_section}\
The code below has each line prefixed with its 1-based line number (the prefixes are not part of the code), \
and the caret is at line {gutter_line}, column {gutter_column}.\n\n\
=== CODE ===\n{numbered}=== END CODE ===\n\n\
Output only the insertion text for the caret position."
    )
}

/// Returns the code with a 1-based line number prefix on every line and a
/// <CURSOR> marker at the given 0-based line and character position,
/// clamped into the line. The prefixes match the IDE's editor gutter, so
/// the model locates the caret by reading instead of counting. LSP columns
/// are UTF-16 units; the clamp uses the line's character count, which can
/// differ only for lines with non-BMP characters.
fn numbered_code_with_cursor(file_content: &str, line: u32, character: u32) -> String {
    let target_line = line as usize;
    let mut out = String::new();
    for (index, text) in file_content.lines().enumerate() {
        out.push_str(&format!("{:4}: ", index + 1));
        if index == target_line {
            let column = (character as usize).min(text.chars().count());
            let before: String = text.chars().take(column).collect();
            let after: String = text.chars().skip(column).collect();
            out.push_str(&before);
            out.push_str("<CURSOR>");
            out.push_str(&after);
        } else {
            out.push_str(text);
        }
        out.push('\n');
    }
    out
}
