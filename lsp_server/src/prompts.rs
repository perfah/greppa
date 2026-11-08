use crate::tick_listener::{AttachedFile, CompilerError};
use tower_lsp::lsp_types::Position;

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

/// Whether the analysis document section with the given key is enabled.
/// The IDE's settings have one checkbox per section of the analysis
/// document; the disabled sections' keys are passed as the
/// ANTIVIBER_DISABLED_SECTIONS environment variable (comma-separated)
/// when the LSP server is launched. Unset or empty means every section
/// is enabled - the default, and the state of plugin builds without
/// the section settings; unknown keys are ignored.
///
/// Resolved here, where the prompt is built, like the guideline: a
/// single consumer, no plumbing through the tick listener. The
/// environment of a running process never changes, so changing the
/// enabled sections takes effect the next time the LSP server starts.
fn section_enabled(key: &str) -> bool {
    match std::env::var("ANTIVIBER_DISABLED_SECTIONS") {
        Ok(disabled) => {
            let disabled = disabled.trim().to_ascii_lowercase();
            if disabled.is_empty() {
                return true;
            }
            !disabled.split(',').any(|entry| entry.trim() == key)
        }
        Err(_) => true,
    }
}

/// Builds the prompt used to ask the LLM to analyze a file in a single
/// inference and produce a Markdown document.
///
/// The document must contain the following sections, in this order, each
/// introduced by a ## heading that matches a tool window section - every
/// section the user enabled in the IDE's settings (see section_enabled;
/// by default all of them):
///
///   ## Scope (topic summary, plus answers to questions found in the code)
///   ## Cursor (the exact part of the code the cursor is in; omitted
///     entirely when no cursor position is known)
///   ## Explanation (Simplified / Technical / With increasing difficulty /
///     Relevant concepts / Diagram)
///   ## Relationship to other files (Depends on / Used by: what the
///     analyzed file takes from each attached file, and what each
///     attached file takes from the analyzed one)
///   ## Parts (the file split into line-range segments)
///   ## Suggested direction (the next steps on a high conceptual level)
///   ## Problems (Compilation and Logical subsections)
///   ## Recommendations (steps covering every problem and the direction)
///
/// Each section is built as its own variable in the function body so it
/// can be toggled: a disabled section contributes no instructions to the
/// prompt, and the intro forbids ## headings other than the listed ones,
/// so the document simply lacks the section. The Recommendations
/// section's coverage requirements adapt to which of its two drivers
/// are enabled (the Problems section and the Suggested direction), and
/// the Problems section references the Suggested direction only while
/// that section is enabled.
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
/// The Suggested direction and Problems sections additionally restate the
/// topic in their own instructions: both sit at the far end of the very
/// long prompt, and the single declaration at the top did not bind them
/// there, so the model wrote them as if no topic existed. When it is
/// None, the analysis is topic-agnostic.
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
/// When `attachments` is non-empty it holds the files the user attached
/// from the Imports pane: the imports the local scan found, plus files
/// added manually - including the quick adds from the recently opened
/// files, which is how a file that imports the analyzed one gets
/// attached to its analysis. They are rendered after the analyzed code
/// as context only: the prompt forbids referencing their lines or
/// suggesting changes to them, because the Line N references and the
/// Replace/Insert/Delete steps drive the IDE's code actions, which can
/// only edit the analyzed file. The Relationship to other files section
/// describes each attachment in both directions: what the analyzed file
/// takes from it, and what it takes from the analyzed file. Empty means
/// no files are attached, and that section says so.
///
/// When `cursor_position` is Some, the code is embedded with a <CURSOR>
/// marker at the caret (see numbered_code_with_cursor) and the document
/// contains a ## Cursor section describing the exact part of the code
/// the cursor is in: the line it is on, the innermost construct
/// containing it, and the enclosing function or method. That semantic
/// window is the section's context window: it adapts to the code (a
/// dense one-liner gets a small window, a spread-out construct a larger
/// one) instead of cutting constructs at an arbitrary fixed line count,
/// which would describe fragments instead of the code the user is
/// actually in. When it is None, no marker is embedded, the Cursor
/// section is omitted entirely, and the Parts section marks no segment
/// as current.
///
/// The code is embedded with a 1-based line number prefix on every line,
/// matching the line numbers the IDE's editor gutter shows. The model must
/// use those numbers for every line reference, so the references match the
/// code exactly - without the prefixes the model had to count the lines
/// itself, which it cannot do reliably and which made the references point
/// at the wrong code. The IDE renders the references as clickable links to
/// the code.
///
/// The Recommendations section must cover the Problems section and the
/// Suggested direction while those sections are enabled: every problem
/// listed in the Problems section must be resolved by at least one
/// step, and at least one step must work towards the Suggested
/// direction. The prompt tells the model to count its problems against
/// its steps, so the required coverage is checkable by the model itself
/// instead of resting on a vague "cover both". With both drivers
/// disabled, the steps address the issues the model itself observes in
/// the code.
///
/// The Problems and Recommendations sections reference a single exact
/// line, never a range: the first line of the code a problem concerns, and
/// for a recommendation the line where the step's code is inserted or
/// the first existing line it replaces. The Parts section keeps line
/// ranges, because its segments are regions of the file by design.
pub fn analysis_prompt(
    cursor_position: Option<Position>,
    file_content: &str,
    topic: Option<&str>,
    errors: &[CompilerError],
    attachments: &[AttachedFile],
) -> String {
    let total_lines = file_content.lines().count().max(1);
    // A Markdown code fence (three backticks), built without a literal fence
    // so this source file stays easy to embed and diff.
    let fence = "`".repeat(3);

    // Which sections the document contains: every section the user
    // enabled in the IDE's settings (see section_enabled). Each section
    // below is built as its own variable that is empty when disabled, so
    // the prompt simply lacks the section's instructions - and with it
    // the section: the intro forbids ## headings other than the listed
    // ones, so the model writes only the enabled sections.
    let scope_enabled = section_enabled("scope");
    let cursor_enabled = section_enabled("cursor");
    let explanation_enabled = section_enabled("explanation");
    let relationship_enabled = section_enabled("relationship");
    let parts_enabled = section_enabled("parts");
    let direction_enabled = section_enabled("direction");
    let problems_enabled = section_enabled("problems");
    let recommendations_enabled = section_enabled("recommendations");

    // The code with a 1-based line number prefix on every line, like the
    // IDE's editor gutter shows them, and - when the cursor position is
    // known - a <CURSOR> marker at the caret (numbered_code_with_cursor,
    // the same mechanism the completion prompt uses). The model
    // references the numbers instead of counting lines itself, which it
    // cannot do reliably and which made the line references point at
    // the wrong code; the marker likewise shows the exact caret, replacing
    // the character index the prompt named before, which the model could
    // not map to a position for the same reason. The no-cursor branch
    // uses the same 4-wide number prefix, so the embedded code looks
    // identical whether or not the position is known.
    let numbered_code = match cursor_position {
        Some(position) => numbered_code_with_cursor(file_content, position.line, position.character),
        None => file_content
            .lines()
            .enumerate()
            .map(|(index, line)| format!("{:4}: {}", index + 1, line))
            .collect::<Vec<_>>()
            .join("\n"),
    };

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
    // and the with-topic prompt names the real topic. Empty when the user
    // disabled the section.
    let scope_section = if !scope_enabled {
        String::new()
    } else {
        match topic {
            Some(topic) => format!(
                "## Scope\n\
Open with one paragraph about the topic '{topic}': describe it, or answer it directly if the topic itself is a question.\n\
Follow with a bullet list (each line starts with '* ') answering every question found in the topic or in the code below - the answers, not the questions. Omit the list when there are no questions.\n"
            ),
            None => "## Scope\n\
Answer every question found in the code below in a bullet list where each line starts with '* ' - the answers, not the questions.\n\
When the code contains no questions, omit this section entirely, heading included.\n"
                .to_string(),
        }
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
            "\n\nThe user attached the file(s) below from the Imports pane because they relate to the analyzed file: the analyzed file may import them, or they may import the analyzed file. \
They are context for understanding the analyzed file only: every Line N reference and every Replace/Insert/Delete step still targets the analyzed file between BEGIN CODE and END CODE, never an attached file, and no step may change an attached file.\n\n{blocks}"
        )
    };

    // The Cursor section's instructions, built per case so the prompt
    // never contains a placeholder the model could echo into the
    // document (the same reasoning as the Scope section). When the
    // position is known, the section describes the exact part of the
    // code the cursor is in, locating it by the <CURSOR> marker in the
    // numbered code below. When no position is known, or the user
    // disabled the section, it is not listed at all, so the document
    // never carries an empty placeholder section.
    let cursor_section = if cursor_enabled {
        match cursor_position {
            Some(_) => "## Cursor\n\
The user's cursor is at the position marked with <CURSOR> in the code below; the marker itself is not part of the code. \
Describe the exact part of the code the cursor is in, in three short numbered parts: \
1. One sentence naming the line the cursor is on, quoting that line's code inline - without the line-number prefix and without the <CURSOR> marker - and referencing the line like: The cursor is on Line 13: `let cache = build_cache();` \
2. A description of the innermost complete construct containing the cursor - the statement, expression, parameter or declaration the marker sits in, or the one it directly follows when it sits between constructs - and what that construct does. \
3. The name of the enclosing function, method or type, and one or two sentences about the role the cursor's construct plays in it. \
Write only about the code at the cursor; do not review the rest of the file in this section.\n"
                .to_string(),
            None => String::new(),
        }
    } else {
        String::new()
    };

    // The Explanation section: what the file does, explained at several
    // levels - simplified, technical, at five levels of increasing
    // difficulty, the key concepts needed to understand it, and an ASCII
    // flowchart of its control flow. Empty when the user disabled the
    // section.
    let explanation_section = if !explanation_enabled {
        String::new()
    } else {
        format!(
            "## Explanation\n\n\
### Simplified\n\
Describe what the user is writing and the purpose of the file, but explain it in a way that is easy to understand by an five/ten year old (explain like i'm 5).\n\n\
### Technical\n\
Describe what the user is writing and what the file as a whole accomplishes. Be informative, technical and elaborate.\n\n\
### With increasing difficulty\n\n\
Explain what the file does in gradually increasing difficulty from level 1 to level 5 using bullet points for each.\n\
Level 1: A simple version ELI5 version that uses limited analogy. Very easy to understand.\n\
...\n\
Level 5: Full technical jargon with precise wording. May demand domain knowledge.\n\n\
Each level should be around 3-5 sentences with the detail necessary to understand the general purpose of the file.\n\n\
### Relevant concepts\n\n\
List a few concepts that are key for understanding this file - the domain ideas, algorithms, data structures, patterns or mechanisms the code is built on, conceptually at the level of the code itself. \
Do not explain trivial programming constructs (loops, variables, basic syntax) unless the file consists of just that. \
List them as bullets (each line starts with '* '), each starting with the concept's name in **bold** followed by one or two sentences.\n\n\
### Diagram\n\n\
Create a compact flowchart in ASCII art showing the file's main control flow - the functions, branches and loops that\n\
matter, not every line. But make it pedagogic / explanatory for a person who is new to what the file is about. \
Wrap it in a fenced code block: a line with {fence}, the diagram, then a closing line with {fence}. \
If the file has no control flow worth visualizing (e.g. data or configuration), write *No flowchart: nothing to\n\
visualize.* instead.\n"
        )
    };

    // The Relationship to other files section: how the analyzed file and
    // each attached file relate, in both directions. The attachments
    // carry no direction tag: the model determines the direction of each
    // relationship from where the references appear - a reference to
    // the attached file in the analyzed code, or a reference to the
    // analyzed file in the attached file's content - and sorts every
    // attachment into the Depends on and Used by subsections
    // accordingly. That is what makes the manual quick adds work: a
    // file that imports the analyzed one, attached from the Imports
    // pane, lands under Used by without any extra plumbing. Empty when
    // the user disabled the section; the attachments themselves stay in
    // the prompt as context either way: they inform every section, not
    // only this one.
    let relationship_section = if !relationship_enabled {
        String::new()
    } else {
        format!(
            "## Relationship to other files\n\
The user attached the file(s) listed after the code from the Imports pane because they relate to the analyzed file. \
If no files are attached, write *No attached files.*\n\
Otherwise, for EVERY attached file, determine the direction of the relationship from where the references are: \
a reference to the attached file in the analyzed code means the analyzed file depends on it, \
and a reference to the analyzed file in the attached file's content means the analyzed file is used by it. \
A file may relate in both directions; every attached file must appear in at least one of the two lists below, \
each introduced by a ### subheading with exactly this text:\n\n\
### Depends on\n\
One bullet per attached file the analyzed file depends on, each starting with the file's name and describing what the analyzed file takes from it: \
which functions, types or values it uses, and the role that file plays for the analyzed file, like: - prompts.rs: Provides the prompt builders that the file calls. \
Write *None.* when no attached file belongs here.\n\n\
### Used by\n\
One bullet per attached file that uses the analyzed file, each starting with the file's name and describing what that file takes from the analyzed one: \
which functions, types or values it uses, and the role the analyzed file plays for it, like: - main.rs: Calls this file's prompt builders to generate its analysis prompts. \
Write *None.* when no attached file belongs here.\n\n\
### Diagram\n\n\
Create a compact flowchart in ASCII art showing how the analyzed file relates to the attached files: \
the analyzed file as one node, every attached file as its own node, and labeled arrows for what flows where: \
an arrow from an attached file to the analyzed file for what the analyzed file takes from it, \
and an arrow from the analyzed file to an attached file for what that file takes from the analyzed one. \
Make it pedagogic / explanatory for a person who is new to what the file is about. \
Wrap it in a fenced code block: a line with {fence}, the diagram, then a closing line with {fence}. \
If no files are attached, write *No flowchart: nothing to visualize.* instead.\n"
        )
    };

    // Whether the Parts section marks the segment containing the cursor.
    // The <CURSOR> marker in the numbered code shows the model where the
    // cursor is, so it can find the segment without position arithmetic.
    let parts_cursor_note = match cursor_position {
        Some(_) => " Mark the segment that contains the <CURSOR> marker by appending **(current)** to its subheading.",
        None => " Do not mark any segment as current.",
    };

    // The Parts section: the file split into line-range segments, each
    // reviewed in a few sentences. Empty when the user disabled the
    // section.
    let parts_section = if !parts_enabled {
        String::new()
    } else {
        format!(
            "## Parts\n\
Split the ENTIRE file into meaningful logical segments; every line of the file must belong to at least one segment, and together the segments must cover the whole file from line 1 through line {total_lines}. \
Give each segment a ### subheading that ends with its 1-based inclusive line range, like: ### Class declaration and fields (Lines 5-12). \
Under each subheading, review the segment in 2-3 sentences about potential issues, bugs or improvements.{parts_cursor_note}\n"
        )
    };

    // The Suggested direction section's instructions, built per case so
    // the section restates the topic when one is set (the same reasoning
    // as the Scope section). The topic is declared once at the top of the
    // prompt, but this section's instructions never named it: by the time
    // the model reached them - at the end of a very long prompt, after
    // the entire numbered code - that declaration no longer bound, and
    // the model read "their goal" out of the code instead of the user's
    // topic. Restating the topic in the section binds it. When no topic
    // is set, the instruction is unchanged. Empty when the user disabled
    // the section.
    let direction_section = if !direction_enabled {
        String::new()
    } else {
        match topic {
            Some(topic) => format!(
                "## Suggested direction\n\n\
What do you suggest the user does next on a high conceptual high level in order to accomplish their goal? \
The user's goal is the topic '{topic}': every suggestion in this section must advance that topic directly, and nothing unrelated to it belongs here.\n"
            ),
            None => "## Suggested direction\n\n\
What do you suggest the user does next on a high conceptual high level in order to accomplish their goal?\n"
                .to_string(),
        }
    };

    // A note for the Logical subsection that restates the topic when one
    // is set, so the problems are found through the topic as well: the
    // subsection's instructions never named it, and the model reported
    // only what a topic-less read of the code finds. When no topic is
    // set, no note is injected and the subsection is unchanged.
    let logical_topic_note = match topic {
        Some(topic) => format!(
            "The user's topic for this review is '{topic}': everything in the file that blocks, misses or contradicts that topic is a problem, and those problems must appear in this list.\n"
        ),
        None => String::new(),
    };

    // A note for the Logical subsection that points at the Suggested
    // direction section, but only while that section is enabled: the
    // document the model writes will not contain it when disabled, and
    // pointing at a section that does not exist would only confuse the
    // model.
    let logical_direction_note = if direction_enabled {
        "If there is an identified 'Suggested direction' above, you may include some problems related to it.\n"
    } else {
        ""
    };

    // The Problems section: the compilation errors the IDE reports and
    // the logical problems the model finds, as two subsections. Empty
    // when the user disabled the section - the IDE-reported errors then
    // simply go unlisted, like every other content of the section.
    let problems_section = if !problems_enabled {
        String::new()
    } else {
        format!(
            "## Problems\n\n\
The next two subsections have bullet lists.\n\
Start EVERY bullet with the exact line number of the first line of the code it concerns, using the 1-based line numbers shown at the start of each code line below, like: - Line 13: Missing error handling in the loop. \
Never use a line range in this section and never invent a line number: copy it from the prefixes of the code below. \
A problem that concerns the whole file uses Line 1.\n\
If there are no problems, write *No problems found.*\n\n\
### Compilation\n\n\
{errors_section}\n\n\
### Logical\n\n\
{logical_topic_note}List the logical problems you see in the file as a whole, not per segment, one bullet per problem, explained properly. \
{logical_direction_note}Be especially attentive if the user is hinting that they need help with something, like implementing a specific algorithm. \
For example: If they write 'I want to implement a peer-to-peer network using rustp2p' then 'Missing implementation for peer-to-peer network' should be considered a problem.\n"
        )
    };

    // The Recommendations section: the concrete next steps, split into the
    // critical ones (with code snippets), the automatable ones (with AI
    // agent prompts) and the trivial ones (with copy-paste snippets the
    // user applies themselves). What the steps must achieve and what they
    // must cover is built from which of the section's two drivers are
    // enabled: the steps cover the Problems section and the Suggested
    // direction only while those sections are part of the document, and
    // referencing a disabled section would ask the model to cover
    // content it was told not to write. Empty when the user disabled the
    // section.
    let recommendations_section = if !recommendations_enabled {
        String::new()
    } else {
        let (goal, coverage) = match (problems_enabled, direction_enabled) {
            (true, true) => (
                "Describe the suggested approaches for solving the problems listed in the Problems section and for advancing the Suggested direction above.",
                "Every step must either resolve one of the listed problems or work towards the Suggested direction; never suggest steps that pull in a different direction than the Suggested direction. \
The steps must cover both of them: EVERY problem listed in the Problems section - in both the Compilation and the Logical subsection - must be resolved by at least one step, and at least one step must work towards the Suggested direction. \
Before writing the steps, count the problems you listed in the Problems section, and verify afterwards that every single one of them is resolved by a step: one problem without a step makes the whole review incomplete.",
            ),
            (true, false) => (
                "Describe the suggested approaches for solving the problems listed in the Problems section.",
                "Every step must resolve one of the listed problems. \
The steps must cover them: EVERY problem listed in the Problems section - in both the Compilation and the Logical subsection - must be resolved by at least one step. \
Before writing the steps, count the problems you listed in the Problems section, and verify afterwards that every single one of them is resolved by a step: one problem without a step makes the whole review incomplete.",
            ),
            (false, true) => (
                "Describe the suggested approaches for advancing the Suggested direction above.",
                "Every step must work towards the Suggested direction; never suggest steps that pull in a different direction than the Suggested direction.",
            ),
            (false, false) => (
                "Describe the suggested approaches for improving the file: the issues and opportunities you see in it.",
                "Every step must address something you actually observed in the code below.",
            ),
        };
        format!(
            "## Recommendations\n\
{goal} {coverage} \
Here it's important to be elaborate: give each step real detail and reasoning.\n\
This is the most important part of the review and must guide the user on what to do next. \
Every step in all three groups below must start with the exact line number where the change happens: the line at which the step's code is inserted, or the FIRST line of the existing code that the step's code replaces. \
Use the 1-based line numbers shown at the start of each code line below, like: 1. Line 13: Refactor the loop to cache the list. \
Never use a line range in this section, never invent a line number (copy it from the prefixes of the code below), and never point at a line inside a suggested code snippet - the reference always points into the analyzed file. \
A step that affects the whole file uses Line 1. \
Split the steps into exactly three groups, in this order, each introduced by a ### subheading with exactly this text:\n\n\
### Critical for understanding\n\
Contains the steps that are essential for understanding the overall program - the design decisions and core logic a human should work through personally. \
Number the steps. For EVERY step, first write a detailed description of how the implementation might look: the approach, the important data structures, the control flow, and why it works. \
Then add a concrete code snippet in a fenced code block - a line with {fence} (optionally followed by the language name), then the code, then a closing line with {fence} - showing that implementation. \
Every step must state what it does to the referenced line by writing one of the words Replace, Insert or Delete right after the line number, like: 1. Line 13: Replace: Refactor the loop to cache the list. \
Replace means the snippet is the new code for the referenced line: applying the step swaps that line for the snippet. \
Insert means the snippet is new code placed directly above the referenced line, and the referenced line itself is kept. \
Delete means the referenced line is removed; the step's fenced code block contains only the word DELETE. \
Write the snippet in the language of the analyzed file. \
Every snippet must be a complete, working implementation: NEVER a placeholder, stub or skeleton, and never a comment like 'placeholder', 'TODO' or 'implement logic here' standing in for real code. \
Steps in this group must NEVER include an AI agent prompt.\n\n\
### Automatable\n\
Contains the steps that are larger chores and repetitive tasks - work that is not central for understanding the overall program but is too big or too tedious to do by hand, so the user can hand them to an AI agent directly. \
Number the steps, one short sentence per step. \
For EVERY step, add a fenced code block with a self-contained prompt that the user can copy-paste to an AI agent to execute the step without any other context. \
The AI agent prompt is the only code block allowed in this group; do not add normal code snippets here. \n\n\
### Trivial\n\
Contains the small mechanical changes the user can easily make themselves - copy-paste edits like adding a missing import, renaming an identifier or fixing a one-liner. \
Number the steps, one short sentence per step. \
For EVERY step, add the exact code to paste as a fenced code block - a line with {fence} (optionally followed by the language name), the code, then a closing line with {fence}. \
The snippet is the only code block allowed in this group; do not add AI agent prompts here.\n\n\
If a group has no steps, write *None.* under its subheading and add one sentence explaining that this can happen while the code does not yet have a clear direction.\n\
If there is nothing to suggest, write *No suggestions.* and omit all three ### subheadings.\n"
        )
    };

    format!(
        "You are an expert code reviewer. Analyze the code at the bottom and answer with a single Markdown document - no text before or after it. \
Start the document directly with its first ## heading. Never wrap the whole document, or any whole section, in a fenced code block or in BEGIN/END-style markers; only the individual code snippets and AI agent prompts described later may be fenced. \
{topic_section}\
{guideline_section}\n\

The document must contain all sections below, in this order, each introduced by a ## heading with exactly the text afterwards:
The text under each heading below tells you what to write; it is never content to copy into the document.
Within the sections, format the content freely with Markdown: use **bold**, *italics*, ### subheadings, bullet and numbered lists, `inline code`, blockquotes and fenced code blocks. \
Do not use # or ## headings other than the section headings listed above. \
Separate every ## section from the next with a horizontal rule: directly before every ## heading except the first one, put a line containing only --- with an empty line before and after it.

Sections:

{scope_section}
{cursor_section}
{explanation_section}
{relationship_section}
{parts_section}
{direction_section}
{problems_section}
{recommendations_section}
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
/// The analysis's recommendations take precedence over continuing the
/// existing code: the prompt names the caret's line and tells the model
/// to first look for a recommendation step whose Line N reference
/// matches it and, when one exists, to complete with that step's code -
/// the part of its snippet that follows the caret. Without that
/// precedence the model treated the analysis as passive context and
/// completed from the file's existing patterns: asked to complete
/// 'g.setColor(' on a line whose recommendation says to switch the
/// color to Color.GRAY, it returned the color the call used before.
/// The Automatable group's fenced blocks are AI agent prompts, not
/// code, so the prompt forbids inserting them.
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

    // The analysis document of the file, when one exists. Its
    // recommendations take precedence over continuing the existing
    // code: the prompt names the caret's line and tells the model to
    // first look for a recommendation step whose Line N reference
    // matches it and, when one exists, to complete with that step's
    // code - the part of its snippet that follows the caret. Without
    // that precedence the model treated the analysis as passive
    // context and completed from the file's existing patterns: asked
    // to complete 'g.setColor(' on a line whose recommendation says to
    // switch the color to Color.GRAY, it returned the color the call
    // used before. The Automatable group's fenced blocks are AI agent
    // prompts, not code, so the prompt forbids inserting them; a step
    // without a code snippet shapes the direction only.
    let analysis_section = match analysis {
        Some(analysis) => format!(
            "A code reviewer analyzed this file; its analysis follows between the ANALYSIS lines. The caret is on line {gutter_line}. \
FIRST check the analysis's Recommendations section for a step whose 'Line N:' reference matches the caret's line (N = {gutter_line}) or the code at the caret. \
When such a step exists, the completion MUST implement that step: output the part of the step's recommended code snippet that continues from the caret, so the completed code matches the recommendation. \
This takes precedence over continuing the file's existing patterns: for example, when a step says the call on the caret's line should use Color.GRAY and the caret sits right after 'g.setColor(', the completion is 'Color.GRAY);' - not the color the call used before. \
The 'Automatable' group's fenced blocks are AI agent prompts, not code: never insert them. \
When no step targets the caret's line, use the analysis as context for what the code does and what changes are planned, so the completion fits the intended direction. \
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
/// differ only for lines with non-BMP characters. Used by both prompts:
/// the completion prompt marks the insertion point, and the analysis
/// prompt marks the caret that the Cursor section describes and whose
/// segment the Parts section marks as current.
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
