package course.langgraph4j.lesson1;

import course.common.ScriptedChatModel;
import course.langgraph4j.common.MessagesState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;

import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LESSON 1 -- StateGraph fundamentals, by rebuilding course.lesson1's raw
 * tool-calling loop (langchain4j-agentic module) as an explicit graph.
 *
 * That lesson was a Java for-loop: send messages, check for tool calls,
 * run them, append results, repeat. Here the SAME loop is two nodes
 * ("agent" calls the model, "tools" runs whatever it asked for) and two
 * edges (an unconditional edge back from "tools" to "agent", and a
 * conditional edge out of "agent" that either loops or ends). Nothing new
 * happens behaviorally -- the point is seeing the loop's shape as a graph,
 * since every later lesson in this package builds on this same
 * StateGraph<AgentState> + node/edge vocabulary.
 *
 * Verified against langgraph4j-core 1.8.26 source: StateGraph's
 * constructor taking (Map<String,Channel<?>> channels, AgentStateFactory
 * stateFactory), AgentState/AgentStateFactory/Channels/Channel,
 * NodeAction/AsyncNodeAction (+ node_async), EdgeAction/AsyncEdgeAction
 * (+ edge_async), addNode/addEdge/addConditionalEdges, START/END
 * (declared on the GraphDefinition interface StateGraph implements), and
 * CompiledGraph.invoke(Map,RunnableConfig).
 *
 * Run: ./gradlew graphLesson1
 */
public class StateGraphFundamentals {

    private static final ToolSpecification GET_DEPLOY_STATUS = ToolSpecification.builder()
            .name("getDeployStatus")
            .description("Check whether a service's latest deploy succeeded")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("service", "the name of the service")
                    .required("service")
                    .build())
            .build();

    private static final ToolSpecification GET_ERROR_RATE = ToolSpecification.builder()
            .name("getErrorRate")
            .description("Get the current error rate percentage for a service")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("service", "the name of the service")
                    .required("service")
                    .build())
            .build();

    private static String getDeployStatus(String service) {
        return service + ": deploy #482 succeeded at 14:02 UTC";
    }

    private static String getErrorRate(String service) {
        return service + ": error rate is 6.8% (baseline: 0.3%)";
    }

    private static String runTool(ToolExecutionRequest request) {
        String service = request.arguments().replaceAll(".*\"service\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return switch (request.name()) {
            case "getDeployStatus" -> getDeployStatus(service);
            case "getErrorRate" -> getErrorRate(service);
            default -> throw new IllegalArgumentException("Unknown tool: " + request.name());
        };
    }

    private static ChatModel buildScriptedModel() {
        AiMessage firstTurn = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("call_1").name("getDeployStatus")
                        .arguments("{\"service\":\"checkout-api\"}").build(),
                ToolExecutionRequest.builder().id("call_2").name("getErrorRate")
                        .arguments("{\"service\":\"checkout-api\"}").build()
        ));
        AiMessage secondTurn = AiMessage.from(
                "checkout-api's deploy #482 succeeded at 14:02 UTC, and its error rate has "
                        + "since jumped to 6.8% (baseline 0.3%) -- the deploy is the likely cause. "
                        + "Recommend rolling back.");
        return new ScriptedChatModel(List.of(firstTurn, secondTurn));
    }

    public static void main(String[] args) throws Exception {
        ChatModel model = buildScriptedModel();
        List<ToolSpecification> tools = List.of(GET_DEPLOY_STATUS, GET_ERROR_RATE);

        AsyncNodeAction<MessagesState> agentNode = node_async(state -> {
            ChatRequest request = ChatRequest.builder()
                    .messages(state.messages())
                    .toolSpecifications(tools)
                    .build();
            ChatResponse response = model.chat(request);
            System.out.println("[agent] model replied: " + response.aiMessage());
            return Map.of("messages", response.aiMessage());
        });

        AsyncNodeAction<MessagesState> toolsNode = node_async(state -> {
            AiMessage last = (AiMessage) state.messages().get(state.messages().size() - 1);
            List<ChatMessage> results = last.toolExecutionRequests().stream()
                    .<ChatMessage>map(req -> {
                        String result = runTool(req);
                        System.out.println("[tools] ran " + req.name() + "(" + req.arguments() + ") -> " + result);
                        return ToolExecutionResultMessage.from(req, result);
                    })
                    .toList();
            return Map.of("messages", results);
        });

        AsyncEdgeAction<MessagesState> routeAfterAgent = edge_async(state -> {
            AiMessage last = (AiMessage) state.messages().get(state.messages().size() - 1);
            return last.hasToolExecutionRequests() ? "continue" : "done";
        });

        StateGraph<MessagesState> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new)
                .addNode("agent", agentNode)
                .addNode("tools", toolsNode)
                .addEdge(START, "agent")
                .addConditionalEdges("agent", routeAfterAgent, Map.of("continue", "tools", "done", END))
                .addEdge("tools", "agent");

        CompiledGraph<MessagesState> compiled = graph.compile();

        RunnableConfig config = RunnableConfig.builder().threadId("lesson1").build();
        MessagesState result = compiled.invoke(
                Map.of("messages", UserMessage.from("Why is checkout-api unhealthy right now?")),
                config
        ).orElseThrow();

        System.out.println("\n=== final transcript ===");
        for (ChatMessage m : result.messages()) {
            System.out.println("[" + m.getClass().getSimpleName() + "] " + m);
        }

        System.out.println(
                "\nThis is the SAME loop as course.lesson1.RawToolCallingLoop (langchain4j-agentic "
                        + "module), rebuilt as an explicit graph: 'agent' and 'tools' are nodes, the "
                        + "loop-back is just an edge, and the decision to loop or stop is a conditional "
                        + "edge instead of a Java for-loop. Every other lesson in this package builds on "
                        + "exactly this StateGraph<AgentState> + node/edge vocabulary.");
    }
}
