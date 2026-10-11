# antiviber (WIP)

## Why

Vibe-coding accelerates development rate particularly in the early stages of development, prototyping, for example. But was this really the hard part?
Finishing a vibe-coded project may paradoxically become more difficult as compared to traditional coding because reliance on the practice
has the potential to severely undercut codebase understanding. This understanding is critical since formation of a mental model is what grants precise adjustments, preventing regression in the intermediate or late stages of development.
In contrast, when the codebase reaches a sufficient degree of complexity, the vibe-coder will often resort to trial-and-error to get the AI agent to non-deterministically guess the solution to a codebase that no one understands.
If context window is cleared, the LLM will often do so as a novice, reflecting an inefficiency in the development cycle. So ironically, some of the issues that emerge in a complex codebase could have been faster to the resolve if the codebase had been coded traditionally.


## What is this

antiviber is a JetBrains IDE plugin that acts as a meta-analysis tool for source code. It aims to mitigate the technical debt and cognitive decay associated with "vibe coding" (over-reliance on non-deterministic AI generation). The tool analyzes code to generate a structured interpretation, including a simplified explanation of the source code, technical summaries, control-flow visualizations, and a segmented breakdown of functionality. Most importantly, it provides a bifurcated recommendation system that separates "Critical for understanding" tasks (requiring human cognitive engagement) from "Automatable" tasks (boilerplate or grind work).

Whereas standard vibe-coding is unlocks immediate productivity gains, the proposed workflow accelerates only some parts of development cycle, while intentionally leaving the human-in-the-loop bottleneck where deemed appropriate.
Any advantages over regular vibe-coding are therefore at best indirect or delayed, possibly only evident for projects on a longer time horizon or those requring a certain attention to detail. The goal is to realign productivity and codebase understanding, such that acceleration only occurs to the extent that both will follow.

## Concrete advantages

1. A more engaging development process that lets you auto-complete YOUR inferences, as opposed to just letting the LLM infer everything altogether.
2. Better understanding of your codebase through a "skim version", pedagogic explanations and diagrams.
3. Reduction in the $\frac{technical debt}{technical understanding}$  ratio assuming adherence to the workflow.

## Features

1. Ask for clarifications directly in your code - the plugin will automatically interpret your source code and determine the best course of action in real-time
1. Overview and simple explanations as well as diagrams of your source code
3. Bifurcation of recommended manual tasks for critical understanding vs  "automatable" grind tasks for agents.
3. Specify goals or topics to focus the interpretation through a certain lens
4. Auto-complete based on the generated interpretation

## Interpretation layout

1. Scope - optional limitation if a topic/goal was specified
2. General direction
   1. simple version for a naive reader
   2. technical version
   3. ASCII graph
3. Listing of current functionality / chunks of the code
4. Listing of identified problems
5. Recommendations
   1. Critical for understanding
   2. Automatable

## Etc

Ironically, and shamelessly, antiviber was vibe coded.