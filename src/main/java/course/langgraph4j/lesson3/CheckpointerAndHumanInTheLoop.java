package course.langgraph4j.lesson3;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LESSON 3 -- Checkpoints and human-in-the-loop: pausing a graph before a
 * destructive action and resuming it only after a human approves.
 *
 * Verified against langgraph4j-core 1.8.26 source:
 * - CompileConfig.Builder.checkpointSaver(...) / .interruptBefore(String...)
 * - MemorySaver (an in-memory BaseCheckpointSaver -- the langgraph4j
 *   analogue of LangGraph Python's InMemorySaver)
 * - RunnableConfig.builder().threadId(...) -- checkpoints are keyed by
 *   thread, exactly like Python LangGraph's configurable.thread_id
 * - CompiledGraph.getState(config) returning a snapshot whose .next()
 *   names the node execution paused before, and .state() returns the
 *   checkpointed state itself (confirmed against the official time-travel
 *   how-to's own usage of both accessors)
 * - GraphInput.resume() -- invoke(Map,...) treats a null inputs map as
 *   GraphInput.resume() internally, but calling invoke(GraphInput.resume(),
 *   config) directly (as used below) says the same thing explicitly,
 *   matching the time-travel how-to's own usage:
 *   graph.stream(GraphInput.resume(), snapshot.config()).
 *
 * The graph itself is deliberately tiny -- diagnose, then rollback -- so
 * the checkpoint/interrupt/resume mechanics are the whole lesson, the same
 * way lesson 1 kept the tool loop tiny to focus on the loop shape itself.
 *
 * Run: ./gradlew graphLesson3
 */
public class CheckpointerAndHumanInTheLoop {

    static class RemediationState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "service", Channels.<String>base(() -> null),
                "summary", Channels.<String>base(() -> null),
                "result", Channels.<String>base(() -> null)
        );

        RemediationState(Map<String, Object> initData) {
            super(initData);
        }

        String service() {
            return this.<String>value("service").orElse("unknown");
        }

        String summary() {
            return this.<String>value("summary").orElse("");
        }

        String result() {
            return this.<String>value("result").orElse("");
        }
    }

    private static CompiledGraph<RemediationState> buildGraph() throws Exception {
        AsyncNodeAction<RemediationState> diagnose = node_async(state -> {
            String summary = state.service() + ": error rate 6.8% (baseline 0.3%), correlates "
                    + "with deploy #482 at 14:02 UTC";
            System.out.println("[diagnose] " + summary);
            return Map.of("summary", summary);
        });

        // The gate that matters: this node only runs after CompileConfig
        // below tells the graph to pause right before it.
        AsyncNodeAction<RemediationState> rollback = node_async(state -> {
            String outcome = state.service() + " rolled back to the previous stable build";
            System.out.println("[rollback] " + outcome);
            return Map.of("result", outcome);
        });

        StateGraph<RemediationState> graph = new StateGraph<>(RemediationState.SCHEMA, RemediationState::new)
                .addNode("diagnose", diagnose)
                .addNode("rollback", rollback)
                .addEdge(START, "diagnose")
                .addEdge("diagnose", "rollback")
                .addEdge("rollback", END);

        CompileConfig compileConfig = CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore("rollback")
                .build();

        return graph.compile(compileConfig);
    }

    private static void runScenario(CompiledGraph<RemediationState> graph, String threadId, String service, boolean approve) throws Exception {
        System.out.println("\n=== incident " + threadId + " (" + service + ") ===");

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        RemediationState paused = graph.invoke(Map.of("service", service), config).orElseThrow();

        var snapshot = graph.getState(config);
        System.out.println("paused before node: " + snapshot.next());
        System.out.println(">>> " + paused.summary());
        System.out.println(">>> a real system would surface this in Slack/PagerDuty and wait here");

        if (approve) {
            System.out.println(">>> on-call engineer APPROVES the rollback -- resuming the paused graph\n");
            RemediationState finalState = graph.invoke(GraphInput.resume(), config).orElseThrow();
            System.out.println("final result: " + finalState.result());
        } else {
            System.out.println(">>> on-call engineer DENIES the rollback -- recording the decision, NOT resuming\n");
            RunnableConfig afterDenial = graph.updateState(
                    config, Map.of("result", "denied by on-call engineer -- rollback not performed"));
            System.out.println("incident left paused; " + graph.getState(afterDenial).state().result());
        }
    }

    public static void main(String[] args) throws Exception {
        CompiledGraph<RemediationState> graph = buildGraph();

        runScenario(graph, "incident-approve", "checkout-api", true);
        runScenario(graph, "incident-deny", "payments-api", false);

        System.out.println(
                "\nBoth incidents ran the exact same graph. The only difference is what a human "
                        + "did at the pause point -- the graph itself has no idea whether it will be "
                        + "approved, denied, or left paused indefinitely; MemorySaver just keeps the "
                        + "checkpoint around until something calls invoke() on that thread again. Swap "
                        + "MemorySaver for one of langgraph4j's durable savers (Postgres/Redis/SQLite, "
                        + "under org.bsc.langgraph4j:langgraph4j-*-saver) and an incident survives a "
                        + "process restart while waiting on approval, same as lesson 5 of the Python "
                        + "course.");
    }
}
