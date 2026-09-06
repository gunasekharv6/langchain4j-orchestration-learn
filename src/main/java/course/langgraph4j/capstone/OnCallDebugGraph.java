package course.langgraph4j.capstone;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * CAPSTONE (langgraph4j) -- the same on-call debugging scenario as the
 * langchain4j-agentic capstone (course.capstone.OnCallDebugAgent), rebuilt
 * as an explicit StateGraph so you can compare the two orchestration
 * styles directly on identical material:
 *
 * - course.capstone.OnCallDebugAgent composes AgenticServices builders
 *   (sequenceBuilder, an AgenticScope-backed shared state, a Java
 *   interface injected as an approval gate).
 * - This class composes StateGraph subgraphs (lesson 4), a conditional
 *   edge (lesson 2), and a checkpoint-backed interrupt (lesson 3) -- the
 *   same three primitives every earlier lesson in this package built up
 *   one at a time.
 *
 * Flow: triage subgraph -> research subgraph -> conditional edge -> if
 * the findings correlate a deploy with the incident, PAUSE before
 * remediate (interruptBefore, MemorySaver-backed) for a human to approve
 * the rollback; otherwise skip straight to synthesize.
 *
 * Run: ./gradlew graphCapstone
 */
public class OnCallDebugGraph {

    static class CapstoneState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "service", Channels.<String>base(() -> null),
                "datadogFinding", Channels.<String>base(() -> null),
                "jenkinsFinding", Channels.<String>base(() -> null),
                "githubFinding", Channels.<String>base(() -> null),
                "confluenceFinding", Channels.<String>base(() -> null),
                "remediationResult", Channels.<String>base(() -> null),
                "summary", Channels.<String>base(() -> null)
        );

        CapstoneState(Map<String, Object> initData) {
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

        String remediationResult() {
            return this.<String>value("remediationResult").orElse("");
        }

        String summary() {
            return this.<String>value("summary").orElse("");
        }
    }

    private static CompiledGraph<CapstoneState> buildTriageSubgraph() throws Exception {
        AsyncNodeAction<CapstoneState> datadog = node_async(state ->
                Map.of("datadogFinding", state.service() + ": 6.8% error rate (baseline 0.3%)"));
        AsyncNodeAction<CapstoneState> jenkins = node_async(state ->
                Map.of("jenkinsFinding", state.service() + ": build #482 deployed at 14:02 UTC"));

        return new StateGraph<>(CapstoneState.SCHEMA, CapstoneState::new)
                .addNode("datadog", datadog)
                .addNode("jenkins", jenkins)
                .addEdge(START, "datadog")
                .addEdge("datadog", "jenkins")
                .addEdge("jenkins", END)
                .compile();
    }

    private static CompiledGraph<CapstoneState> buildResearchSubgraph() throws Exception {
        AsyncNodeAction<CapstoneState> github = node_async(state -> Map.of(
                "githubFinding", "commit a1b2c3 'refactor PaymentValidator null checks', merged 13:58 UTC"));
        AsyncNodeAction<CapstoneState> confluence = node_async(state -> Map.of(
                "confluenceFinding", "runbook: if error spike follows a deploy, roll back first, investigate after"));

        return new StateGraph<>(CapstoneState.SCHEMA, CapstoneState::new)
                .addNode("github", github)
                .addNode("confluence", confluence)
                .addEdge(START, "github")
                .addEdge("github", "confluence")
                .addEdge("confluence", END)
                .compile();
    }

    private static CompiledGraph<CapstoneState> buildGraph() throws Exception {
        CompiledGraph<CapstoneState> triageSubgraph = buildTriageSubgraph();
        CompiledGraph<CapstoneState> researchSubgraph = buildResearchSubgraph();

        AsyncNodeAction<CapstoneState> remediate = node_async(state -> {
            String outcome = state.service() + " rolled back to the previous stable build (build #481)";
            System.out.println("[remediate] " + outcome);
            return Map.of("remediationResult", outcome);
        });

        AsyncEdgeAction<CapstoneState> needsRemediation = edge_async(state ->
                (state.jenkinsFinding().contains("deployed") && state.githubFinding().contains("refactor"))
                        ? "remediate" : "skip");

        AsyncNodeAction<CapstoneState> synthesize = node_async(state -> {
            String summary = "Incident summary for " + state.service() + ": error rate spiked right after "
                    + state.jenkinsFinding() + ", which included " + state.githubFinding() + ". "
                    + "Per runbook (" + state.confluenceFinding() + "), "
                    + (state.remediationResult().isEmpty()
                            ? "no rollback was needed."
                            : state.remediationResult() + ".")
                    + " Recommend a follow-up PR review before re-deploying that change.";
            return Map.of("summary", summary);
        });

        StateGraph<CapstoneState> graph = new StateGraph<>(CapstoneState.SCHEMA, CapstoneState::new)
                .addSubgraph("triage", triageSubgraph)
                .addSubgraph("research", researchSubgraph)
                .addNode("remediate", remediate)
                .addNode("synthesize", synthesize)
                .addEdge(START, "triage")
                .addEdge("triage", "research")
                .addConditionalEdges("research", needsRemediation, Map.of("remediate", "remediate", "skip", "synthesize"))
                .addEdge("remediate", "synthesize")
                .addEdge("synthesize", END);

        CompileConfig compileConfig = CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore("remediate")
                .build();

        return graph.compile(compileConfig);
    }

    public static void main(String[] args) throws Exception {
        CompiledGraph<CapstoneState> graph = buildGraph();

        RunnableConfig config = RunnableConfig.builder().threadId("incident-checkout-api-001").build();
        System.out.println("=== Incident opened: 'checkout-api is throwing errors' ===\n");

        CapstoneState afterResearch = graph.invoke(Map.of("service", "checkout-api"), config).orElseThrow();

        var snapshot = graph.getState(config);
        System.out.println("[triage]     " + afterResearch.datadogFinding());
        System.out.println("[triage]     " + afterResearch.jenkinsFinding());
        System.out.println("[research]   " + afterResearch.githubFinding());
        System.out.println("[research]   " + afterResearch.confluenceFinding());
        System.out.println("\n>>> PAUSED (next node: " + snapshot.next() + ") for human approval before rollback");
        System.out.println(">>> a real system would surface this in Slack/PagerDuty/a UI and wait");
        System.out.println(">>> approving now, as the on-call engineer would after reviewing the above:\n");

        CapstoneState finalState = graph.invoke(GraphInput.resume(), config).orElseThrow();
        System.out.println(finalState.summary());

        System.out.println(
                "\n--- Compare with course.capstone.OnCallDebugAgent ---\n"
                        + "Same scenario, same four fake integrations, same human-approval requirement.\n"
                        + "There: a supervisor's sequenceBuilder() pipeline plus an injected approval\n"
                        + "interface. Here: two composed StateGraph subgraphs, a conditional edge, and\n"
                        + "a checkpoint-backed interruptBefore. Neither is 'more correct' -- they're\n"
                        + "langchain4j-agentic's and langgraph4j's two different answers to the same\n"
                        + "orchestration problem, and now you've built both.");
    }
}
