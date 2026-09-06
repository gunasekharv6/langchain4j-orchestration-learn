package course.lesson1;

import course.common.ScriptedChatModel;
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

import java.util.ArrayList;
import java.util.List;

/**
 * LESSON 1 -- The tool-calling loop, by hand.
 *
 * Before learning any framework abstraction (AiServices, the agentic
 * module), see exactly what it abstracts. Every agent framework -- on any
 * language, any provider -- is a loop shaped like this:
 *
 *   1. Send the conversation so far to a model that knows about some tools
 *      (a ToolSpecification per tool: name, description, JSON parameter
 *      schema).
 *   2. The model replies with either a final answer, or a request to call
 *      one or more tools (a ToolExecutionRequest: name + JSON arguments).
 *   3. If tools were requested: actually run them in your code, wrap each
 *      result as a ToolExecutionResultMessage, append it to the
 *      conversation, and go back to step 1.
 *   4. If a final answer came back: stop.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson1.RawToolCallingLoop
 */
public class RawToolCallingLoop {

    // --- 1. Describe the tools the model is allowed to call --------------
    // In lesson 2, @Tool-annotated methods generate this for you
    // reflectively. Here we build it by hand to see what's underneath.

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

    // --- 2. The actual tool implementations -------------------------------

    private static String getDeployStatus(String service) {
        return service + ": deploy #482 succeeded at 14:02 UTC";
    }

    private static String getErrorRate(String service) {
        return service + ": error rate is 6.8% (baseline: 0.3%)";
    }

    // --- 3. Script what the model will say each time it's called -----------
    // A real model decides this dynamically. We hardcode the sequence here
    // so the LOOP MECHANICS are what you're studying, not model behavior.
    // Notice: two tool calls requested in the first turn, then a final
    // answer with no tool calls.

    private static ChatModel buildScriptedModel() {
        AiMessage firstTurn = AiMessage.from(List.of(
                ToolExecutionRequest.builder()
                        .id("call_1")
                        .name("getDeployStatus")
                        .arguments("{\"service\":\"checkout-api\"}")
                        .build(),
                ToolExecutionRequest.builder()
                        .id("call_2")
                        .name("getErrorRate")
                        .arguments("{\"service\":\"checkout-api\"}")
                        .build()
        ));
        AiMessage secondTurn = AiMessage.from(
                "checkout-api's deploy #482 succeeded at 14:02 UTC, and its error rate has "
                        + "since jumped to 6.8% (baseline 0.3%) -- the deploy is the likely cause. "
                        + "Recommend rolling back."
        );
        return new ScriptedChatModel(List.of(firstTurn, secondTurn));
    }

    // --- 4. The loop itself -------------------------------------------------

    private static List<ChatMessage> runAgentLoop(ChatModel model, String userInput, int maxSteps) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(userInput));
        List<ToolSpecification> tools = List.of(GET_DEPLOY_STATUS, GET_ERROR_RATE);

        for (int step = 0; step < maxSteps; step++) {
            System.out.println("\n--- step " + step + ": sending " + messages.size() + " message(s) to the model ---");

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build();
            ChatResponse response = model.chat(request);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                System.out.println("model returned a final answer, no tool calls -> stop");
                break;
            }

            System.out.println("model requested " + aiMessage.toolExecutionRequests().size() + " tool call(s)");
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                String result = executeTool(toolRequest);
                System.out.println("  ran " + toolRequest.name() + "(" + toolRequest.arguments() + ") -> " + result);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }
        }
        return messages;
    }

    // A tiny hand-rolled dispatcher. Real code would parse the JSON
    // arguments properly; this is intentionally minimal to keep the loop
    // itself the focus.
    private static String executeTool(ToolExecutionRequest request) {
        String service = request.arguments().replaceAll(".*\"service\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return switch (request.name()) {
            case "getDeployStatus" -> getDeployStatus(service);
            case "getErrorRate" -> getErrorRate(service);
            default -> throw new IllegalArgumentException("Unknown tool: " + request.name());
        };
    }

    public static void main(String[] args) {
        ChatModel model = buildScriptedModel();
        List<ChatMessage> transcript = runAgentLoop(model, "Why is checkout-api unhealthy right now?", 5);

        System.out.println("\n=== full transcript ===");
        for (ChatMessage m : transcript) {
            System.out.println("[" + m.getClass().getSimpleName() + "] " + m);
        }

        System.out.println(
                "\nEverything AiServices (lesson 2) does is: run exactly this loop for you, "
                        + "plus reflection over @Tool methods, memory, and structured output. "
                        + "Nothing magic -- you just built it.");
    }
}
