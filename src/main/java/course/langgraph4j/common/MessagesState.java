package course.langgraph4j.common;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared AgentState shape for the langgraph4j lessons that carry a running
 * chat transcript. One channel, "messages", built with Channels.appender --
 * confirmed against the official StateGraph javadoc example and against
 * AppenderChannel's update() logic in langgraph4j-core 1.8.26: a node
 * returning Map.of("messages", oneMessage) appends that single message,
 * and Map.of("messages", listOfMessages) appends every element of the list.
 */
public class MessagesState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "messages", Channels.appender(ArrayList::new)
    );

    public MessagesState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages() {
        return (List<ChatMessage>) value("messages").orElseGet(ArrayList::new);
    }
}
