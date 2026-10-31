# Greppa

**Greppa** is an interactive tool for long-term software projects, where consistent progress depends on genuine understanding. AI-assisted “vibe coding” accelerates prototyping and implementation, but it also widens the distance between developers and their own projects. Because modern AI agents are eager to take the initiative, the real bottleneck often becomes alignment: between the agent, the software, and the developer’s intent.

Where conventional AI coding hands control to an agent that rewrites the code, Greppa works differently. It operates as a closed loop: the AI observes the changes the developer makes, responds with feedback, and completes the parts the developer hadn’t considered. Developers decide how much to lean on the AI, whatever their experience level, simply by adjusting how often it checks in.

The key distinction is that Greppa doesn’t assume the agent should hold all the initiative. The plugin explicitly separates tasks that are essential to understanding the project from those straightforward enough to automate. The aim is to support the mental model a developer needs to truly understand their own software, and to close the distance that AI-driven development tends to create.

## Features

### Interpret view
* Grasp code quickly: an LLM interprets your code and generates a clear, informative markdown summary
* Get explanations of varying difficulty (level 1-5) to match your current level
* Get an automatically generated diagram that visualizes what the code does
* Receive explanations focused on the code under your cursor
* Navigate with ease: files are broken into explained segments, each linked to its source code by a blue line you can click

### Improve view
* Sorts tasks into "Critical for understanding" and "Automatable", guided by your own configurable guidelines

### Topic/goal
* Optionally set a topic or goal to focus on; it shapes most or all generated content

### JetBrains integration
* Auto-complete code based on the generated markdown
* Get "code action" suggestions

### Configuration

* **LLM base URL:** the endpoint of your OpenAI-compatible provider
* **LLM model:** the name of the model to use
* **LLM API key:** optional, if your provider requires one
* **LLM guideline:** an optional, personal guideline that steers how the LLM responds; it is simply added to the prompt

### Compatibility
* Works with any OpenAI-compatible provider (see the [OpenAI OpenAPI specification](https://github.com/openai/openai-openapi)). See the Ollama example configuration below.
## Screenshots

### Interpret view

![Interpret view](./assets/interpret.png)

### Improve view:

![Improve view](./assets/improve.png)

### Settings view

![Settings](./assets/settings.png)

## Reservations

I vibe-coded this project and take no credit for it.