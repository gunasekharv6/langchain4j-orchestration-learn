package course.langgraph4j.lesson4;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LESSON 4 -- Composing specialists: subgraphs instead of a supervisor package.
 *
 * The Python course's multi-agent lesson leans on two extra packages,
 * langgraph-supervisor and langgraph-swarm, and the langchain4j-agentic
 * course (this project's other module) has its own dedicated
 * AgenticServices.supervisorBuilder(). langgraph4j-core ships neither --
 * verified by reading its own root pom.xml module list (langgraph4j-core,
 * langgraph4j-bom, the langchain4j/spring-ai integration modules, several
 * checkpoint-saver modules, and a studio UI; no supervisor/swarm module).
 * The idiomatic way to compose specialists here is simpler and lower-level:
 * compile each specialist as its OWN StateGraph, then attach the resulting
 * CompiledGraph as a single node in a parent graph with
 * StateGraph.addSubgraph(String, CompiledGraph<State>) -- confirmed against
 * StateGraph.java's addSubgraph/addNode(CompiledGraph) overloads. A
 * subgraph and its parent must share the same AgentState type: the
 * subgraph's nodes just read and write the same shared state as any other
 * node in the parent.
 *
 * This graph also folds in what lesson 2 taught: after the triage
 * subgraph runs, a conditional edge decides whether the research
 * subgraph is even worth running.
 *
 * Run: ./gradlew graphLesson4
 */
public class SubgraphsAndComposition {

    static class IncidentState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "service", Channels.<String>base(() -> null),
                "datadogFinding", Channels.<String>base(() -> null),
                "jenkinsFinding", Channels.<String>base(() -> null),
                "githubFinding", Channels.<String>base(() -> null),
                "confluenceFinding", Channels.<String>base(() -> null),
                "summary", Channels.<String>base(() -> null)
        );

        IncidentState(Map<String, Object> initData) {
            super(initData);
        }

        String service() {
            return this.<String>value("service").orElse("unknown");
        }

        String datadogFinding() {
            return this.<String>value("datadogFinding").orElse("");
        }

        String jenkinsFinding() {
            return this.<String>value("jenkinsFinding").orElse("");
        }

        String githubFinding() {
            return this.<String>value("githubFinding").orElse("");
        }

        String confluenceFinding() {
            return this.<String>value("confluenceFinding").orElse("");
        }

        String summary() {
            return this.<String>value("summary").orElse("");
        }
    }

    private static CompiledGraph<IncidentState> buildTriageSubgraph(boolean deployHappened) throws Exception {
        AsyncNodeAction<IncidentState> datadog = node_async(state ->
                Map.of("datadogFinding", state.service() + ": 6.8% error rate (baseline 0.3%)"));

        AsyncNodeAction<IncidentState> jenkins = node_async(state -> Map.of(
                "jenkinsFinding", deployHappened
                        ? state.service() + ": build #482 deployed at 14:02 UTC"
                        : state.service() + ": no deploy in the last 24h"
        ));

        return new StateGraph<>(IncidentState.SCHEMA, IncidentState::new)
                .addNode("datadog", datadog)
                .addNode("jenkins", jenkins)
                .addEdge(START, "datadog")
                .addEdge("datadog", "jenkins")
                .addEdge("jenkins", END)
                .compile();
    }

    private static CompiledGraph<IncidentState> buildResearchSubgraph() throws Exception {
        AsyncNodeAction<IncidentState> github = node_async(state -> Map.of(
                "githubFinding", "commit a1b2c3 'refactor PaymentValidator null checks', merged 13:58 UTC"));

        AsyncNodeAction<IncidentState> confluence = node_async(state -> Map.of(
                "confluenceFinding", "runbook: if error spike follows a deploy, roll back first, investigate after"));

        return new StateGraph<>(IncidentState.SCHEMA, IncidentState::new)
                .addNode("github", github)
                .addNode("confluence", confluence)
                .addEdge(START, "github")
                .addEdge("github", "confluence")
                .addEdge("confluence", END)
                .compile();
    }

    private static void runScenario(String label, boolean deployHappened) throws Exception {
        System.out.println("\n=== " + label + " (deploy happened: " + deployHappened + ") ===");

        CompiledGraph<IncidentState> triageSubgraph = buildTriageSubgraph(deployHappened);
        CompiledGraph<IncidentState> researchSubgraph = buildResearchSubgraph();

        AsyncEdgeAction<IncidentState> needsResearch = edge_async(state ->
                state.jenkinsFinding().contains("deployed") ? "investigate" : "skip");

        AsyncNodeAction<IncidentState> synthesize = node_async(state -> {
            StringBuilder summary = new StringBuilder("Incident summary for " + state.service() + ": "
                    + state.datadogFinding() + "; " + state.jenkinsFinding());
            if (!state.githubFinding().isEmpty()) {
                summary.append("; ").append(state.githubFinding());
            }
            if (!state.confluenceFinding().isEmpty()) {
                summary.append("; ").append(state.confluenceFinding());
            }
            return Map.of("summary", summary.toString());
        });

        CompiledGraph<IncidentState> parent = new StateGraph<>(IncidentState.SCHEMA, IncidentState::new)
                .addSubgraph("triage", triageSubgraph)
                .addSubgraph("research", researchSubgraph)
                .addNode("synthesize", synthesize)
                .addEdge(START, "triage")
                .addConditionalEdges("triage", needsResearch, Map.of("investigate", "research", "skip", "synthesize"))
                .addEdge("research", "synthesize")
                .addEdge("synthesize", END)
                .compile();

        RunnableConfig config = RunnableConfig.builder().threadId(label).build();
        IncidentState result = parent.invoke(Map.of("service", "checkout-api"), config).orElseThrow();
        System.out.println(result.summary());
    }

    public static void main(String[] args) throws Exception {
        runScenario("deploy-correlated", true);
        runScenario("no-recent-deploy", false);

        System.out.println(
                "\nEach specialist (triage, research) is a complete, independently testable "
                        + "StateGraph -- you could invoke buildTriageSubgraph(...) on its own in a "
                        + "unit test. Composing them is just addSubgraph plus an ordinary conditional "
                        + "edge; there's no separate hand-off protocol to learn beyond what lessons 1 "
                        + "and 2 already covered.");
    }
}
