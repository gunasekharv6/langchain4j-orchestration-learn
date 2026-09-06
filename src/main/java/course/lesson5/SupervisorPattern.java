package course.lesson5;

import course.common.ScriptedChatModel;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

/**
 * LESSON 5 -- the supervisor pattern.
 *
 * IMPORTANT NOTE ON THIS LESSON: langchain4j-agentic ships a genuine
 * LLM-DRIVEN supervisor (AgenticServices.supervisorBuilder()) where the
 * planner model itself decides, at runtime, which sub-agent to call next.
 * That's the true analogue of LangGraph's supervisor pattern from the
 * Python course. Its exact internal planning contract (what the planner
 * model's response has to look like) isn't part of the module's public,
 * stable documentation, so this course can't respect its own rule of
 * "every lesson runs deterministically with no API key" for that specific
 * API -- scripting a fake response for an undocumented internal protocol
 * would be guessing, not teaching.
 *
 * So this lesson does two things:
 *   PART A (runs, no API key needed): the DETERMINISTIC core of a
 *   supervisor -- delegate to triage, then research, then remediation,
 *   then synthesize -- built with the sequential workflow from lesson 3.
 *   This is exactly what a supervisor does when the delegation ORDER is
 *   fixed; what a real LLM-driven supervisor adds on top is choosing
 *   that order (and which agents to skip) dynamically.
 *
 *   PART B (reference code, NOT executed here): the real
 *   supervisorBuilder() API, copied accurately from the official docs.
 *   Swap in a real ChatModel (see the README) and try it yourself --
 *   that's the one lesson in this course you verify against a live model
 *   rather than a scripted one.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson5.SupervisorPattern
 */
public class SupervisorPattern {

    interface TriageAgent {
        @UserMessage("Triage this incident using monitoring signals. Service: {{service}}")
        @Agent("Triages incidents using monitoring signals")
        String triage(@V("service") String service);
    }

    interface ResearchAgent {
        @UserMessage("Research the root cause given this triage finding: {{triage}}")
        @Agent("Researches root cause from source control and runbooks")
        String research(@V("triage") String triage);
    }

    interface RemediationAgent {
        @UserMessage("Propose a remediation action given this research: {{research}}")
        @Agent("Proposes a remediation action")
        String remediate(@V("research") String research);
    }

    interface IncidentSynthesizer {
        @UserMessage("""
                Write a final one-paragraph incident summary combining:
                Triage: {{triage}}
                Research: {{research}}
                Remediation: {{remediation}}
                """)
        @Agent("Synthesizes the final incident summary")
        String synthesize(
                @V("triage") String triage,
                @V("research") String research,
                @V("remediation") String remediation);
    }

    // ============================================================
    // PART A -- deterministic delegation order (runs with no API key)
    // ============================================================
    static void runDeterministicSupervisor() {
        System.out.println("=== PART A: supervisor-shaped sequential pipeline ===");

        TriageAgent triageAgent = AgenticServices.agentBuilder(TriageAgent.class)
                .chatModel(ScriptedChatModel.of(AiMessage.from(
                        "checkout-api error rate is 6.8% (baseline 0.3%), correlating with deploy #482 at 14:02 UTC.")))
                .outputKey("triage")
                .build();

        ResearchAgent researchAgent = AgenticServices.agentBuilder(ResearchAgent.class)
                .chatModel(ScriptedChatModel.of(AiMessage.from(
                        "Latest commit touched null checks in PaymentValidator -- likely regression from deploy #482.")))
                .outputKey("research")
                .build();

        RemediationAgent remediationAgent = AgenticServices.agentBuilder(RemediationAgent.class)
                .chatModel(ScriptedChatModel.of(AiMessage.from(
                        "Roll back checkout-api to the build before #482.")))
                .outputKey("remediation")
                .build();

        IncidentSynthesizer synthesizer = AgenticServices.agentBuilder(IncidentSynthesizer.class)
                .chatModel(ScriptedChatModel.of(AiMessage.from(
                        "checkout-api's error rate spiked after deploy #482 introduced a PaymentValidator "
                                + "regression; recommended action is an immediate rollback.")))
                .outputKey("summary")
                .build();

        UntypedAgent supervisorPipeline = AgenticServices.sequenceBuilder()
                .subAgents(triageAgent, researchAgent, remediationAgent, synthesizer)
                .outputKey("summary")
                .build();

        Object result = supervisorPipeline.invoke(Map.of("service", "checkout-api"));

        System.out.println("triage -> research -> remediation -> synthesis, in that fixed order");
        System.out.println("final: " + result);
    }

    // ============================================================
    // PART B -- the real, LLM-driven supervisor (reference only)
    // ============================================================

    /**
     * NOT called from main(). This is accurate syntax straight from the
     * langchain4j docs, adapted to this lesson's agents -- try it with a
     * real ChatModel:
     *
     *   ChatModel plannerModel = init a real model (see README);
     *   SupervisorAgent incidentSupervisor = buildRealLlmSupervisor(
     *           triageAgent, researchAgent, remediationAgent, plannerModel);
     *   String result = incidentSupervisor.invoke("checkout-api is throwing errors, investigate and fix it");
     *
     * Here, plannerModel itself decides how many of the three agents to
     * call, in what order, purely by reading their descriptions -- no
     * fixed sequence at all.
     */
    static SupervisorAgent buildRealLlmSupervisor(
            TriageAgent triageAgent,
            ResearchAgent researchAgent,
            RemediationAgent remediationAgent,
            ChatModel plannerModel) {

        return AgenticServices.supervisorBuilder()
                .chatModel(plannerModel)
                .subAgents(triageAgent, researchAgent, remediationAgent)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .build();
    }

    public static void main(String[] args) {
        runDeterministicSupervisor();

        System.out.println(
                "\nSee buildRealLlmSupervisor() in this file's source for the real, "
                        + "LLM-planned version -- swap in a real ChatModel and give it a try.");
    }
}
