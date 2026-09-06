package course.langgraph4j.lesson2;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LESSON 2 -- Command: updating state and choosing the next node in one step.
 *
 * Lesson 1 routed with addConditionalEdges: a node writes to state, then a
 * SEPARATE edge function reads that state back to decide where to go next.
 * That's two steps and two places to keep in sync. A node can instead
 * return a Command(routeKey, stateUpdate) and do both atomically -- this
 * is langgraph4j's equivalent of Python LangGraph's Command(goto=, update=).
 *
 * Verified against langgraph4j-core 1.8.26 source: StateGraph.addNode(String,
 * AsyncCommandAction<State>, Map<String,String> mappings), the Command
 * record (action/Command.java), and AsyncCommandAction (action/
 * AsyncCommandAction.java). The `mappings` map is the same safety net
 * addConditionalEdges uses: it declares every route key a Command might
 * return up front, so the graph can validate all possible destinations
 * exist at build time instead of failing at runtime on a typo.
 *
 * This file builds the SAME triage-and-route logic two ways so you can
 * compare them directly: buildCommandStyleGraph() (one node, one atomic
 * decision) and buildClassicStyleGraph() (a plain node plus a separate
 * addConditionalEdges call, the style lesson 1 already used).
 *
 * Run: ./gradlew graphLesson2
 */
public class CommandAndRouting {

    static class TriageState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "service", Channels.<String>base(() -> null),
                "errorRatePct", Channels.<Double>base(() -> 0.0),
                "severity", Channels.<String>base(() -> null),
                "note", Channels.<String>base(() -> null)
        );

        TriageState(Map<String, Object> initData) {
            super(initData);
        }

        String service() {
            return this.<String>value("service").orElse("unknown");
        }

        double errorRatePct() {
            return this.<Double>value("errorRatePct").orElse(0.0);
        }

        String severity() {
            return this.<String>value("severity").orElse("unknown");
        }

        String note() {
            return this.<String>value("note").orElse("");
        }
    }

    private static AsyncNodeAction<TriageState> escalateNode() {
        return node_async(state -> Map.of(
                "note", "Paged on-call for " + state.service() + " (severity=" + state.severity() + ")"
        ));
    }

    private static AsyncNodeAction<TriageState> autoResolveNode() {
        return node_async(state -> Map.of(
                "note", "Auto-resolved " + state.service() + " (severity=" + state.severity() + "); no page needed"
        ));
    }

    // --- Style A: one node decides the update AND the route, atomically ---

    private static CompiledGraph<TriageState> buildCommandStyleGraph() throws Exception {
        AsyncCommandAction<TriageState> classifyAndRoute = (state, config) -> {
            double rate = state.errorRatePct();
            boolean critical = rate >= 5.0;
            String routeKey = critical ? "critical" : "low";
            System.out.println("[classify+route] " + state.service() + " error rate " + rate
                    + "% -> " + routeKey + " (state + routing decided together)");
            return CompletableFuture.completedFuture(
                    new Command(routeKey, Map.of("severity", critical ? "critical" : "low"))
            );
        };

        return new StateGraph<>(TriageState.SCHEMA, TriageState::new)
                .addNode("classify", classifyAndRoute, Map.of("critical", "escalate", "low", "auto_resolve"))
                .addNode("escalate", escalateNode())
                .addNode("auto_resolve", autoResolveNode())
                .addEdge(START, "classify")
                .addEdge("escalate", END)
                .addEdge("auto_resolve", END)
                .compile();
    }

    // --- Style B: classic two-step -- a node updates state, a separate edge routes ---

    private static CompiledGraph<TriageState> buildClassicStyleGraph() throws Exception {
        AsyncNodeAction<TriageState> classifyOnly = node_async(state -> {
            boolean critical = state.errorRatePct() >= 5.0;
            return Map.of("severity", critical ? "critical" : "low");
        });

        AsyncEdgeAction<TriageState> routeBySeverity = edge_async(TriageState::severity);

        return new StateGraph<>(TriageState.SCHEMA, TriageState::new)
                .addNode("classify", classifyOnly)
                .addNode("escalate", escalateNode())
                .addNode("auto_resolve", autoResolveNode())
                .addEdge(START, "classify")
                .addConditionalEdges("classify", routeBySeverity, Map.of("critical", "escalate", "low", "auto_resolve"))
                .addEdge("escalate", END)
                .addEdge("auto_resolve", END)
                .compile();
    }

    private static void run(String label, CompiledGraph<TriageState> graph, String service, double errorRate) throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(label + "-" + service).build();
        TriageState result = graph.invoke(
                Map.of("service", service, "errorRatePct", errorRate),
                config
        ).orElseThrow();
        System.out.println("  -> " + result.note());
    }

    public static void main(String[] args) throws Exception {
        CompiledGraph<TriageState> commandStyle = buildCommandStyleGraph();
        CompiledGraph<TriageState> classicStyle = buildClassicStyleGraph();

        System.out.println("=== Command style (one atomic step) ===");
        run("cmd", commandStyle, "checkout-api", 6.8);
        run("cmd", commandStyle, "search-api", 1.2);

        System.out.println("\n=== Classic style (node, then separate conditional edge) ===");
        run("classic", classicStyle, "checkout-api", 6.8);
        run("classic", classicStyle, "search-api", 1.2);

        System.out.println(
                "\nSame outcome either way. Prefer Command when the routing decision and the "
                        + "state update are conceptually one decision (as here); prefer "
                        + "addConditionalEdges when several different nodes need to share the same "
                        + "routing rule, since then the rule is worth factoring out on its own.");
    }
}
