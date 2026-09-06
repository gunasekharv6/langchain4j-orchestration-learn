package course.capstone;

import course.common.ScriptedChatModel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;
import java.util.Map;

/**
 * CAPSTONE -- an on-call debugging supervisor pipeline, in Java.
 *
 * The Java equivalent of the Python course's capstone, modeled on your
 * own hackathon project: a fixed-order delegation (lesson 5's
 * supervisor-shaped pipeline) across a TRIAGE specialist (Datadog +
 * Jenkins tools), a RESEARCH specialist (GitHub + Confluence tools), and
 * a REMEDIATION specialist whose destructive tool is gated behind human
 * approval (lesson 6) -- ending in a synthesized incident summary.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.capstone.OnCallDebugAgent
 */
public class OnCallDebugAgent {

    // ---------------------------------------------------------------
    // Tools -- stand-ins for your MCP server's real Jenkins/GitHub/
    // Datadog/Confluence integrations. Swapping these method bodies for
    // real API/MCP calls is a drop-in change.
    // ---------------------------------------------------------------

    static class MonitoringTools {
        @Tool("Get the current error rate for a service from Datadog")
        String datadogGetErrorRate(@P("the service name") String service) {
            return service + ": 6.8% error rate over the last 15 min (baseline 0.3%)";
        }

        @Tool("Get the most recent Jenkins deploy info for a service")
        String jenkinsGetLastDeploy(@P("the service name") String service) {
            return service + ": build #482 deployed at 14:02 UTC by ci-bot";
        }
    }

    static class ResearchTools {
        @Tool("Get the most recent commits merged to a service's main branch")
        String githubGetRecentCommits(@P("the service name") String service) {
            return service + ": commit a1b2c3 'refactor PaymentValidator null checks', merged 13:58 UTC";
        }

        @Tool("Search the team's Confluence runbooks for relevant guidance")
        String confluenceSearchRunbook(@P("search query") String query) {
            return "Runbook 'Checkout incidents': if error spike follows a deploy, roll back first, investigate after.";
        }
    }

    interface ApprovalGateService {
        boolean approve(String action, String details);
    }

    static class AutoApprovingGate implements ApprovalGateService {
        @Override
        public boolean approve(String action, String details) {
            System.out.println("  >>> PAUSED for human approval: " + action + " (" + details + ")");
            System.out.println("  >>> a real system would surface this in Slack/PagerDuty and wait");
            System.out.println("  >>> approving now, as the on-call engineer would after reviewing the above");
            return true;
        }
    }

    static class RemediationTools {
        private final ApprovalGateService approvalGate;

        RemediationTools(ApprovalGateService approvalGate) {
            this.approvalGate = approvalGate;
        }

        @Tool("Roll back the most recent deploy for a service. DESTRUCTIVE -- requires approval.")
        String rollbackDeploy(@P("the service name") String service) {
            boolean approved = approvalGate.approve("rollback_deploy", "service=" + service);
            if (!approved) {
                return "Rollback of " + service + " was NOT approved; no action taken.";
            }
            return service + " rolled back to the previous stable build";
        }
    }

    // ---------------------------------------------------------------
    // Specialists
    // ---------------------------------------------------------------

    public interface TriageAgent {
        @UserMessage("Triage this incident for {{service}} using Datadog and Jenkins.")
        @Agent("Triages incidents using Datadog and Jenkins")
        String triage(@V("service") String service);
    }

    public interface ResearchAgent {
        @UserMessage("Research the root cause for {{service}} using GitHub history and runbooks, given triage: {{triage}}")
        @Agent("Researches root cause using GitHub and Confluence")
        String research(@V("service") String service, @V("triage") String triage);
    }

    public interface RemediationAgent {
        @UserMessage("Propose and execute remediation for {{service}}, given research: {{research}}")
        @Agent("Proposes and executes remediation")
        String remediate(@V("service") String service, @V("research") String research);
    }

    public interface IncidentSynthesizer {
        @UserMessage("""
                Write a final incident summary for {{service}} combining:
                Triage: {{triage}}
                Research: {{research}}
                Remediation: {{remediation}}
                """)
        @Agent("Synthesizes the final incident summary")
        String synthesize(
                @V("service") String service,
                @V("triage") String triage,
                @V("research") String research,
                @V("remediation") String remediation);
    }

    public static void main(String[] args) {
        System.out.println("=== Incident opened: 'checkout-api is throwing errors' ===\n");

        ChatModel triageModel = new ScriptedChatModel(List.of(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("t1").name("datadogGetErrorRate")
                                .arguments("{\"service\":\"checkout-api\"}").build(),
                        ToolExecutionRequest.builder().id("t2").name("jenkinsGetLastDeploy")
                                .arguments("{\"service\":\"checkout-api\"}").build()
                )),
                AiMessage.from("checkout-api's error rate is 6.8% (baseline 0.3%), correlating with "
                        + "deploy #482 at 14:02 UTC.")
        ));
        TriageAgent triageAgent = AgenticServices.agentBuilder(TriageAgent.class)
                .chatModel(triageModel)
                .tools(new MonitoringTools())
                .outputKey("triage")
                .build();

        ChatModel researchModel = new ScriptedChatModel(List.of(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("r1").name("githubGetRecentCommits")
                                .arguments("{\"service\":\"checkout-api\"}").build(),
                        ToolExecutionRequest.builder().id("r2").name("confluenceSearchRunbook")
                                .arguments("{\"query\":\"checkout error spike after deploy\"}").build()
                )),
                AiMessage.from("The latest commit touched null checks in PaymentValidator -- likely "
                        + "regression. Runbook says roll back first, investigate after.")
        ));
        ResearchAgent researchAgent = AgenticServices.agentBuilder(ResearchAgent.class)
                .chatModel(researchModel)
                .tools(new ResearchTools())
                .outputKey("research")
                .build();

        ChatModel remediationModel = new ScriptedChatModel(List.of(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("m1").name("rollbackDeploy")
                                .arguments("{\"service\":\"checkout-api\"}").build()
                )),
                AiMessage.from("Rolled back checkout-api to the previous stable build.")
        ));
        RemediationAgent remediationAgent = AgenticServices.agentBuilder(RemediationAgent.class)
                .chatModel(remediationModel)
                .tools(new RemediationTools(new AutoApprovingGate()))
                .outputKey("remediation")
                .build();

        ChatModel synthesizerModel = ScriptedChatModel.of(AiMessage.from(
                "Incident summary for checkout-api: error rate spiked to 6.8% right after build #482 "
                        + "(14:02 UTC), which included a PaymentValidator null-check change. Per runbook, "
                        + "rolled back after approval. Recommend a follow-up PR review before re-deploying."
        ));
        IncidentSynthesizer synthesizer = AgenticServices.agentBuilder(IncidentSynthesizer.class)
                .chatModel(synthesizerModel)
                .outputKey("summary")
                .build();

        UntypedAgent onCallPipeline = AgenticServices.sequenceBuilder()
                .subAgents(triageAgent, researchAgent, remediationAgent, synthesizer)
                .outputKey("summary")
                .build();

        Object summary = onCallPipeline.invoke(Map.of("service", "checkout-api"));

        System.out.println("\n=== final summary ===");
        System.out.println(summary);

        System.out.println(
                "\n--- To make this real ---\n"
                        + "1. Swap each ScriptedChatModel for a real ChatModel (see README).\n"
                        + "2. Swap each tool's body for a real API/MCP call -- your hackathon project's\n"
                        + "   Jenkins/GitHub/Datadog/Confluence integrations plug straight in here.\n"
                        + "3. Swap AutoApprovingGate for one that posts to Slack/PagerDuty and blocks\n"
                        + "   (or returns immediately and resumes the pipeline later via a callback).\n"
                        + "4. Compare this fixed-order pipeline to lesson 5 Part B's real LLM-driven\n"
                        + "   supervisorBuilder() -- that's the version where the model itself decides\n"
                        + "   which specialist to call and in what order.");
    }
}
