package course.lesson6;

import course.common.ScriptedChatModel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * LESSON 6 -- gating a destructive action behind human approval.
 *
 * LangGraph has a first-class primitive for this (interrupt() /
 * HumanInTheLoopMiddleware from the Python course): the framework itself
 * pauses graph execution and persists state until a human responds.
 * langchain4j-agentic's equivalent building blocks are newer and less
 * settled, so the robust, idiomatic Java way to do this today is simpler
 * and arguably more natural for a Spring/backend engineer: put the gate
 * INSIDE the tool itself, as an injected dependency -- exactly like a
 * guard clause or an authorization check in any other service class.
 *
 * The @Tool method doesn't know or care whether the approval came from a
 * hardcoded "yes" (as in this demo), a blocking call to a Slack/PagerDuty
 * approval workflow, or a row in a database that a human updates later
 * via a different endpoint -- that's the ApprovalGateService abstraction's job.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson6.ApprovalGate
 */
public class ApprovalGate {

    interface ApprovalGateService {
        boolean approve(String action, String details);
    }

    /** Stands in for paging a human and blocking until they respond. */
    static class AutoApprovingGate implements ApprovalGateService {
        @Override
        public boolean approve(String action, String details) {
            System.out.println("  >>> APPROVAL NEEDED: " + action + " (" + details + ")");
            System.out.println("  >>> a real system would page Slack/PagerDuty and block here; auto-APPROVING for this demo");
            return true;
        }
    }

    static class AutoDenyingGate implements ApprovalGateService {
        @Override
        public boolean approve(String action, String details) {
            System.out.println("  >>> APPROVAL NEEDED: " + action + " (" + details + ")");
            System.out.println("  >>> auto-DENYING for this demo (simulates a human clicking 'reject')");
            return false;
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

    interface RemediationAssistant {
        String chat(String message);
    }

    /**
     * modelFinalAnswer is what we SCRIPT the model to say after seeing the
     * tool's result -- a real model would naturally phrase its reply
     * around whatever the tool actually returned (approved or not).
     */
    static void runScenario(String label, ApprovalGateService gate, String modelFinalAnswer) {
        System.out.println("=== " + label + " ===");

        ChatModel model = new ScriptedChatModel(List.of(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder()
                                .id("call_1")
                                .name("rollbackDeploy")
                                .arguments("{\"service\":\"checkout-api\"}")
                                .build()
                )),
                AiMessage.from(modelFinalAnswer)
        ));

        RemediationAssistant assistant = AiServices.builder(RemediationAssistant.class)
                .chatModel(model)
                .tools(new RemediationTools(gate))
                .build();

        String answer = assistant.chat("Roll back checkout-api.");
        System.out.println("assistant: " + answer);
    }

    public static void main(String[] args) {
        runScenario(
                "Scenario 1: approved",
                new AutoApprovingGate(),
                "Done -- checkout-api has been rolled back."
        );

        System.out.println();

        runScenario(
                "Scenario 2: denied",
                new AutoDenyingGate(),
                "Rollback was not approved, so no changes were made."
        );

        System.out.println(
                "\nIn production, AutoApprovingGate/AutoDenyingGate become one class that "
                        + "posts to Slack/PagerDuty and blocks (or better: returns immediately and the "
                        + "workflow resumes later via a callback/webhook) -- the @Tool method and the "
                        + "AiServices wiring around it never change.");
    }
}
