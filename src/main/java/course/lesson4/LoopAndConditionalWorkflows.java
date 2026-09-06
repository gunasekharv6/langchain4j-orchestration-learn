package course.lesson4;

import course.common.ScriptedChatModel;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

/**
 * LESSON 4 -- langchain4j-agentic: loop and conditional workflows.
 *
 * LOOP: re-run a pair of agents (score, then edit) until an exit
 * condition over the shared AgenticScope is satisfied, or a max
 * iteration count is hit -- the langchain4j analogue of LangGraph's
 * cyclic edges (lesson 3's route_by_severity + back-edge, generalized).
 *
 * CONDITIONAL: route to exactly one of several agents based on a
 * predicate over the scope -- the langchain4j analogue of LangGraph's
 * add_conditional_edges / Command routing (lesson 3-4 in the Python course).
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson4.LoopAndConditionalWorkflows
 */
public class  LoopAndConditionalWorkflows {

    // ============================================================
    // PART A -- loop workflow
    // ============================================================

    public interface QualityScorer {
        @UserMessage("""
                Score the quality of this incident postmortem draft from 0.0 to 1.0.
                Return ONLY the number, nothing else.
                Draft: {{draft}}
                """)
        @Agent("Scores postmortem draft quality")
        Double score(@V("draft") String draft);
    }

    public interface DraftEditor {
        @UserMessage("""
                Improve this postmortem draft; its current quality score is {{score}}.
                Draft: {{draft}}
                """)
        @Agent("Edits and improves the postmortem draft")
        String edit(@V("draft") String draft, @V("score") Double score);
    }

    static void runLoop() {
        System.out.println("=== PART A: loop workflow ===");

        // First pass scores low, second pass scores high enough to exit.
        // Extra scripted entries are a safety margin -- ScriptedChatModel
        // clamps to the last one if the loop calls a model more times
        // than expected, so this stays deterministic either way.
        ChatModel scorerModel = new ScriptedChatModel(java.util.List.of(
                AiMessage.from("0.5"),
                AiMessage.from("0.9")
        ));
        ChatModel editorModel = new ScriptedChatModel(java.util.List.of(
                AiMessage.from("Checkout went down at 14:02 UTC due to deploy #482; rollback completed at 14:15 UTC."),
                AiMessage.from("Checkout went down at 14:02 UTC due to deploy #482 (bad null check in PaymentValidator); "
                        + "rollback completed at 14:15 UTC; follow-up PR review scheduled.")
        ));

        QualityScorer scorer = AgenticServices.agentBuilder(QualityScorer.class)
                .chatModel(scorerModel)
                .outputKey("score")
                .build();

        DraftEditor editor = AgenticServices.agentBuilder(DraftEditor.class)
                .chatModel(editorModel)
                .outputKey("draft")
                .build();

        UntypedAgent reviewLoop = AgenticServices.loopBuilder()
                .subAgents(scorer, editor)
                .maxIterations(3)
                .exitCondition(scope -> scope.readState("score", 0.0) >= 0.8)
                .outputKey("draft")
                .build();

        Object finalDraft = reviewLoop.invoke(Map.of(
                "draft", "Checkout went down. We don't know why yet."
        ));

        System.out.println("scorer + editor alternated until score >= 0.8 or 3 iterations");
        System.out.println("final draft: " + finalDraft);
    }

    // ============================================================
    // PART B -- conditional workflow
    // ============================================================

    public interface AutoResolver {
        @UserMessage("Write a one-sentence log entry: this low-severity incident on {{service}} was auto-resolved.")
        @Agent("Auto-resolves low severity incidents")
        String resolve(@V("service") String service);
    }

    public interface OncallPager {
        @UserMessage("Write a one-sentence page message for on-call about a high-severity incident on {{service}}.")
        @Agent("Pages on-call for high severity incidents")
        String page(@V("service") String service);
    }

    static void runConditional() {
        System.out.println("\n=== PART B: conditional workflow ===");

        ChatModel autoResolverModel = ScriptedChatModel.of(
                AiMessage.from("search-api: minor blip auto-resolved, no action needed."));
        ChatModel oncallPagerModel = ScriptedChatModel.of(
                AiMessage.from("PAGE: checkout-api is down, on-call engineer needed immediately."));

        AutoResolver autoResolver = AgenticServices.agentBuilder(AutoResolver.class)
                .chatModel(autoResolverModel)
                .outputKey("action")
//                .tools()
                .build();

        OncallPager oncallPager = AgenticServices.agentBuilder(OncallPager.class)
                .chatModel(oncallPagerModel)
                .outputKey("action")
                .build();

        UntypedAgent triageRouter = AgenticServices.conditionalBuilder()
                .subAgents(scope -> "high".equals(scope.readState("severity", "")), oncallPager)
                .subAgents(scope -> "low".equals(scope.readState("severity", "")), autoResolver)
                .outputKey("action")
                .build();

        System.out.println("-- high severity --");
        System.out.println(triageRouter.invoke(Map.of("service", "checkout-api", "severity", "high")));

        System.out.println("-- low severity --");
        System.out.println(triageRouter.invoke(Map.of("service", "search-api", "severity", "low")));
    }

    public static void main(String[] args) {
        runLoop();
        runConditional();
    }
}
