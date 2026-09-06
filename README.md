# LangChain4j orchestration course (Java)

A step-by-step, hands-on path through `langchain4j` + `langchain4j-agentic`
orchestration -- the Java-native counterpart to the Python LangChain/
LangGraph course, built around your Spring Boot / Java background.

## Important: how this course was verified (please read)

Every lesson in the Python version of this course was actually run and
its output captured before delivery. **This Java course could not be
verified the same way.** The sandboxed environment I work in (and the
isolated VM this session uses on your computer) both block outbound
access to Maven Central by network policy, so I was never able to run
`mvn compile` against these files myself.

To compensate, every class in this course was written against **exact
API signatures fetched from the actual `langchain4j` 1.19.0 source code
and official documentation** -- not from memory or guesswork. Every
`AgenticServices` method, every builder's method list, `ChatModel`'s
real interface, `AgenticScope`'s methods, `Planner`/`Action` -- all
copy-verified against source. Lesson 5 is explicit about the one place
I deliberately avoided guessing: the real LLM-driven `supervisorBuilder()`
API's internal planning contract isn't public/stable enough to script a
fake response for safely, so that lesson runs a deterministic stand-in
and gives you the real API as accurate reference code to try with a live
model.

The project now builds with **Gradle** (converted from the original
Maven `pom.xml`, kept as `pom.xml.bak` in case you ever want it back) --
a full Gradle wrapper is already checked in, so `./gradlew` needs
nothing pre-installed. `build.gradle` uses the Groovy DSL.

**What I need from you:** run `./gradlew build` (which needs your own
internet access the first time, since it downloads the Gradle 8.14.3
distribution and resolves the Maven Central dependencies -- both of
those hosts are blocked from my own tools). If anything fails to
compile, paste me the error and I'll fix it immediately -- that feedback
loop is the verification step I couldn't do myself.

## Setup

1. If IntelliJ still has this open as the old Maven project, close it and
   re-open the folder (`langchain4j-orchestration-course`) -- IntelliJ
   should now auto-detect `build.gradle` and import it as a Gradle
   project instead. (You may need File -> Project Structure to point the
   Gradle JVM at a 17+ SDK; your `.jdks` folder has several to pick from.)
2. From a terminal in the project folder, build everything and let Gradle
   resolve dependencies:
   ```
   ./gradlew build
   ```
   (Windows without Git Bash/WSL: `gradlew.bat build`.) The very first run
   downloads the full Gradle 8.14.3 distribution and the Maven Central
   dependencies, so it needs your real internet access and will be slow;
   later runs are fast and fully offline-capable.
3. Run any lesson's `main` method directly from IntelliJ (right-click ->
   Run), or from a terminal with the matching Gradle task -- one is
   registered per lesson:
   ```
   ./gradlew lesson1
   ./gradlew lesson2
   ./gradlew lesson3
   ./gradlew lesson4
   ./gradlew lesson5
   ./gradlew lesson6
   ./gradlew capstone
   ```

## Why every lesson runs with no API key

`course.common.ScriptedChatModel` implements `ChatModel` and plays back a
fixed list of `AiMessage` responses (including tool-call requests)
instead of calling a real provider. This isolates the FRAMEWORK's
behavior -- routing, state updates, tool loops -- from a real model's
nondeterminism, so you can watch the mechanics deterministically. Swap it
for a real model with one line (see below) once you're ready.

## Part 1: `course.*` -- the langchain4j-agentic module

(Each item's Gradle task name is the lowercase word before the class name, e.g. `./gradlew lesson1` for `RawToolCallingLoop`, `./gradlew capstone` for `OnCallDebugAgent`.)

1. `course.lesson1.RawToolCallingLoop` -- the raw `ChatModel` + `ToolSpecification` loop, no framework. Everything else is this loop plus bookkeeping.
2. `course.lesson2.AiServicesBasics` -- `AiServices`, `@Tool` methods, `ChatMemory`. The langchain4j analogue of `create_agent`.
3. `course.lesson3.SequentialAndParallelWorkflows` -- `langchain4j-agentic`'s sequential and parallel builders, sharing state through `AgenticScope`.
4. `course.lesson4.LoopAndConditionalWorkflows` -- the loop builder (exit conditions) and conditional builder (predicate-based routing).
5. `course.lesson5.SupervisorPattern` -- a deterministic supervisor-shaped pipeline (runs here), plus the real LLM-driven `supervisorBuilder()` as verified reference code (try with a real model).
6. `course.lesson6.ApprovalGate` -- gating a destructive `@Tool` behind human approval, the idiomatic Java pattern (an injected approval dependency) since the framework's own HITL primitives are newer/less stable than LangGraph's `interrupt()`.
7. `course.capstone.OnCallDebugAgent` -- everything combined: a triage/research/remediation pipeline with fake Jenkins/GitHub/Datadog/Confluence tools (swap for your real MCP integrations) and an approval gate before rollback.

## Making a lesson use a real model

Everywhere a lesson builds `new ScriptedChatModel(...)` or
`ScriptedChatModel.of(...)`, replace it with a real `ChatModel`, e.g. for
Anthropic:

```java
// build.gradle: uncomment the langchain4j-anthropic dependency line
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-sonnet-4-6")
        .build();
```

Nothing downstream changes -- `AiServices`, `AgenticServices`, and every
builder in this course accept any `ChatModel`, which is exactly what
`ScriptedChatModel` and a real provider client both are.

## Part 2: `course.langgraph4j.*` -- LangGraph itself, in Java

Everything above is `langchain4j-agentic`: LangChain4j's own orchestration
module, with its own builders (sequence/parallel/loop/conditional/
supervisor). It is NOT the same library as **LangGraph4j**
(`org.bsc.langgraph4j`), which is an independent, MIT-licensed project that
ports LangGraph's actual graph model -- `StateGraph`, nodes, edges,
conditional edges, checkpoints -- to Java. It's designed to interoperate
with LangChain4j and Spring AI, but it's a different dependency (added to
`build.gradle` as `org.bsc.langgraph4j:langgraph4j-core:1.8.26`) and a
different API, closer in spirit to the Python `LangGraph` half of your
other course than to `langchain4j-agentic`.

Every class here was written against exact API signatures fetched from
the real `langgraph4j-core` 1.8.26 source on GitHub (`StateGraph`,
`AgentState`/`AgentStateFactory`, `Channels`/`Channel`,
`NodeAction`/`AsyncNodeAction`, `EdgeAction`/`AsyncEdgeAction`,
`Command`/`AsyncCommandAction`, `CompileConfig`, `RunnableConfig`,
`MemorySaver`, `CompiledGraph`, `GraphInput`) plus the project's own
published how-to guides for the parts that needed a real usage example
(the human-in-the-loop / time-travel pattern) -- the same
source-verification discipline as Part 1, and the same caveat: I could not
compile or run this against Maven Central myself, for the same network
reasons explained above. Please run it and report any compiler errors.

Run order (each lesson also stands alone as a unit you could copy into a
real project):

1. `course.langgraph4j.lesson1.StateGraphFundamentals` -- rebuilds
   `course.lesson1.RawToolCallingLoop`'s hand-rolled tool loop as an
   explicit two-node graph (`agent`, `tools`) with a conditional edge
   deciding whether to loop or stop. This is the one worth re-reading --
   every later langgraph4j lesson reuses this exact
   `StateGraph<AgentState>` + node/edge vocabulary.
2. `course.langgraph4j.lesson2.CommandAndRouting` -- the same
   update-state-and-choose-the-next-node problem solved two ways: a
   `Command` returned from one node (atomic), versus a plain node plus a
   separate `addConditionalEdges` call (the style lesson 1 used) -- run
   side by side so you can see exactly when each is preferable.
3. `course.langgraph4j.lesson3.CheckpointerAndHumanInTheLoop` --
   `MemorySaver` + `CompileConfig.interruptBefore(...)` to pause a graph
   before a destructive node and resume it (or not) based on a human
   decision. The langgraph4j analogue of Python LangGraph's
   `InMemorySaver` + `interrupt()`.
4. `course.langgraph4j.lesson4.SubgraphsAndComposition` -- langgraph4j-core
   has no dedicated supervisor/swarm package (confirmed by reading its own
   root `pom.xml` module list); the idiomatic way to compose specialists
   is compiling each as its own `StateGraph`, then attaching it to a
   parent graph with `addNode(id, compiledSubgraph)`, routed with an ordinary
   conditional edge.
5. `course.langgraph4j.capstone.OnCallDebugGraph` -- the identical
   on-call-debugging scenario as `course.capstone.OnCallDebugAgent` (same
   fake Datadog/Jenkins/GitHub/Confluence signals, same
   human-approval-before-rollback requirement), rebuilt from subgraphs +
   a conditional edge + a checkpoint-backed interrupt instead of
   `AgenticServices` builders -- run both capstones back to back to see
   the two orchestration styles solve the same problem.

```
./gradlew graphLesson1
./gradlew graphLesson2
./gradlew graphLesson3
./gradlew graphLesson4
./gradlew graphCapstone
```

## A note on `langchain4j-agentic`'s maturity

This module is genuinely beta (version suffixes like `1.19.0-beta29`
track the core `1.19.0` release). The API surface used here is faithful
to that exact version, but the module is evolving quickly -- methods seen
only on the `main` branch (like a `plannerBuilder()` convenience method)
were deliberately avoided in favor of what's confirmed present in the
pinned release, to keep this course reproducible. Expect some API
movement if you track newer releases.

## Suggested pace

- Day 1: lessons 1-2 (the tool-calling loop, by hand and then via `AiServices`)
- Day 2-3: lessons 3-4 (sequential/parallel, then loop/conditional) -- this is the material worth re-reading, since everything else builds on `AgenticScope`
- Day 4: lesson 5 (supervisor) -- try the real `supervisorBuilder()` with a live model once lesson 5 runs cleanly
- Day 5: lesson 6, then the capstone -- rebuild the capstone against your real MCP integrations as a portfolio piece, same as the Python course's suggestion
- Day 6-7: `course.langgraph4j` lessons 1-2 (StateGraph fundamentals, then Command/routing) -- lesson 1 is the one worth re-reading here, same role as lesson 3 played in the Python course
- Day 8: `course.langgraph4j` lessons 3-4 (checkpoints/human-in-the-loop, then subgraph composition)
- Day 9: the langgraph4j capstone -- run it right after the langchain4j-agentic capstone and compare the two orchestration styles on the same scenario

## Where to go from here

- Official docs: https://docs.langchain4j.dev
- Agentic tutorial: https://docs.langchain4j.dev/tutorials/agents/
- Source (for when docs lag the code, which happens with a beta module): https://github.com/langchain4j/langchain4j/tree/main/langchain4j-agentic
- LangGraph4j docs: https://langgraph4j.github.io/langgraph4j/
- LangGraph4j source: https://github.com/langgraph4j/langgraph4j
- LangGraph4j worked examples (multi-agent hand-off, adaptive RAG, MCP client agents, and more): https://github.com/langgraph4j/langgraph4j-examples
