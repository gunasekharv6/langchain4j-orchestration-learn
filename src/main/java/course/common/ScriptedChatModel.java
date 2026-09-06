package course.common;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A chat model that plays back a fixed, scripted sequence of AiMessage
 * responses -- including tool-call requests -- instead of calling a real
 * LLM provider.
 *
 * WHY THIS EXISTS: every lesson in this course needs to run with no API
 * key, so you can see exactly what LangChain4j / the agentic module DO
 * with a model's output (routing, state updates, hand-offs) without a
 * real model's nondeterminism in the way. Swapping in a real model later
 * is a one-line change -- see the README.
 *
 * ChatModel (dev.langchain4j.model.chat.ChatModel) is a plain interface
 * where every method has a default implementation; the one meant to be
 * overridden is doChat(ChatRequest), which chat(ChatRequest) delegates to
 * after running listeners. Overriding just that one method is the entire
 * integration point -- confirmed against the langchain4j-core 1.19.0
 * source.
 */
public class ScriptedChatModel implements ChatModel {

    private final List<AiMessage> script;
    private final AtomicInteger index = new AtomicInteger(0);

    public ScriptedChatModel(List<AiMessage> script) {
        if (script.isEmpty()) {
            throw new IllegalArgumentException("script must contain at least one AiMessage");
        }
        this.script = script;
    }

    /** Convenience factory: ScriptedChatModel.of(AiMessage.from("hi"), ...) */
    public static ScriptedChatModel of(AiMessage... responses) {
        return new ScriptedChatModel(List.of(responses));
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        int i = Math.min(index.getAndIncrement(), script.size() - 1);
        AiMessage response = script.get(i);
        return ChatResponse.builder().aiMessage(response).build();
    }
}
