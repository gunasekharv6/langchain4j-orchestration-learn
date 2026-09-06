package course.lesson2;

import course.common.ScriptedChatModel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * LESSON 2 -- AiServices: the declarative agent layer.
 *
 * AiServices.builder(SomeInterface.class) turns a plain Java interface
 * into a running agent: it wires up the exact tool-calling loop you wrote
 * by hand in lesson 1 (reflectively converting every @Tool method into a
 * ToolSpecification, running the loop, and parsing the final answer into
 * your method's return type), and adds ChatMemory so a conversation
 * remembers earlier turns. This is the langchain4j analogue of
 * LangChain's create_agent: same idea (model + tools + a declarative
 * wrapper), different flavor -- an interface + annotations instead of a
 * factory function.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson2.AiServicesBasics
 */
public class AiServicesBasics {

    // --- The agent's public shape: just a plain interface -------------------
    interface IncidentAssistant {
        String chat(String message);
    }

//    You define their behavior declaratively—mainly through the method name, parameters, annotations, and return type. LangChain4j creates the actual implementation dynamically when .build() runs.
//    For example:
//    interface IncidentAssistant {
//
//        @SystemMessage("You are an incident-response assistant. Be concise.")
//        String chat(@UserMessage String message);
//
//        @SystemMessage("Summarize the incident in 3 bullet points.")
//        String summarize(@UserMessage String incidentDetails);
//    }

    // --- Tools: a plain Java object; @Tool methods become ToolSpecifications
    // reflectively -- this is exactly what you built by hand in lesson 1.
    static class MonitoringTools {
        @Tool("Get the current error rate percentage for a service")
        String getErrorRate(@P("the name of the service") String service) {
            return service + ": error rate is 6.8% (baseline 0.3%)";
        }
    }

    public static void main(String[] args) {
        // Three scripted model turns:
        //  1) the model asks to call getErrorRate for checkout-api
        //  2) the model answers using that tool result
        //  3) on a SECOND external call, the model answers a memory question
        //     using context AiServices carried forward automatically
        ChatModel model = new ScriptedChatModel(List.of(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder()
                                .id("call_1")
                                .name("getErrorRate")
                                .arguments("{\"service\":\"checkout-api\"}")
                                .build()
                )),
                AiMessage.from("checkout-api's error rate is 6.8%, well above the 0.3% baseline."),
                AiMessage.from("You asked about checkout-api's error rate a moment ago.")
        ));

        // ChatMemory is what makes turn 2 able to refer back to turn 1.
        // MessageWindowChatMemory keeps the last N messages; swap for a
        // persistent implementation (e.g. backed by a database) in
        // production the same way LangGraph's checkpointer is swappable.
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

        IncidentAssistant assistant = AiServices.builder(IncidentAssistant.class)
                .chatModel(model)
                .tools(new MonitoringTools())
                .chatMemory(memory)
                .build();

        System.out.println("=== turn 1 ===");
        String turn1 = assistant.chat("How is checkout-api doing?");
        System.out.println("assistant: " + turn1);

        System.out.println("\n=== turn 2 (memory check) ===");
        String turn2 = assistant.chat("What did I just ask about?");
        System.out.println("assistant: " + turn2);

        System.out.println(
                "\nNotice you never touched a message list or a loop here -- "
                        + "AiServices ran lesson 1's exact loop internally, once per "
                        + "external chat() call (twice for turn 1, since a tool call "
                        + "was involved), and ChatMemory carried the transcript forward "
                        + "so turn 2 could refer back to it.");
    }
}
/*
interface IncidentAssistant {

    @SystemMessage("You are an incident-response assistant. Be concise.")
    String chat(@UserMessage String message);

    @SystemMessage("Summarize the incident in 3 bullet points.")
    String summarize(@UserMessage String incidentDetails);
}
There is no Java method body because LangChain4j generates a proxy implementation.
 Conceptually, it does this when you call assistant.summarize(details):
// Simplified idea — LangChain4j does this internally
String prompt = "Summarize the incident in 3 bullet points.\n" + details;
String answer = model.chat(prompt);
return answer;
So:
        •
You define the intended task with annotations/prompts.
•
LangChain4j implements the plumbing: prompt creation, model call, memory, and tool calling.
•
The LLM produces the actual answer at runtime.
Without clear annotations or a strong method-level prompt, a method is just a general “ask the model using this input”
entry point; its name alone is not a reliable instruction to the model.
*/