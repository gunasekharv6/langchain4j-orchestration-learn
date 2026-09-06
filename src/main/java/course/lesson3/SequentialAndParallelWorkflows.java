package course.lesson3;

import course.common.ScriptedChatModel;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LESSON 3 -- langchain4j-agentic: sequential and parallel workflows.
 *
 * The agentic module coordinates several AiServices-style agents that all
 * share one AgenticScope -- a map of named variables. Each agent reads
 * some variables (via @V-bound parameters) and writes one (via
 * outputKey()); a workflow builder decides WHEN each agent runs.
 *
 * This lesson covers the two simplest workflows:
 *   SEQUENTIAL: agents run one after another, each seeing what earlier
 *   agents wrote to the scope.
 *   PARALLEL: independent agents run concurrently, then their outputs are
 *   merged with an explicit output(...) function.
 *
 * Run: mvn compile exec:java -Dexec.mainClass=course.lesson3.SequentialAndParallelWorkflows
 */
public class SequentialAndParallelWorkflows {

    // ============================================================
    // PART A -- sequential workflow
    // ============================================================

    interface SeverityClassifier {
        @UserMessage("""
                Classify incident severity as exactly one word: "high" or "low".
                Rule: if the error rate is above 5%, answer "high", otherwise "low".
                Error rate: {{errorRate}}
                Return ONLY the single word.
                """)
        @Agent("Classifies incident severity from an error rate reading")
        String classify(@V("errorRate") String errorRate);
    }

    interface IncidentSummarizer {
        @UserMessage("""
                Write a one-sentence incident summary for {{service}} given severity={{severity}}.
                """)
        @Agent("Writes a final incident summary")
        String summarize(@V("service") String service, @V("severity") String severity);
    }

    static void runSequential() {
        System.out.println("=== PART A: sequential workflow ===");

        ChatModel classifierModel = ScriptedChatModel.of(AiMessage.from("high"));
        ChatModel summarizerModel = ScriptedChatModel.of(AiMessage.from(
                "checkout-api is experiencing a HIGH severity incident and needs immediate attention."));

        SeverityClassifier severityClassifier = AgenticServices.agentBuilder(SeverityClassifier.class)
                .chatModel(classifierModel)
                .outputKey("severity")
                .build();

        IncidentSummarizer incidentSummarizer = AgenticServices.agentBuilder(IncidentSummarizer.class)
                .chatModel(summarizerModel)
                .outputKey("summary")
                .build();

        // sequenceBuilder() with no argument returns an UNTYPED agent:
        // invoke it with a Map of the starting variables, get back
        // whatever the LAST agent's outputKey points to.
        UntypedAgent triagePipeline = AgenticServices.sequenceBuilder()
                .subAgents(severityClassifier, incidentSummarizer)
                .outputKey("summary")
                .build();

        Object result = triagePipeline.invoke(Map.of(
                "service", "checkout-api",
                "errorRate", "6.8%"
        ));

        System.out.println("classifier ran first (reads errorRate, writes severity)");
        System.out.println("summarizer ran second (reads service + severity, writes summary)");
        System.out.println("final result: " + result);
    }

    // ============================================================
    // PART B -- parallel workflow
    // ============================================================

    interface DatadogChecker {
        @UserMessage("Report the current error rate for {{service}} in one short sentence.")
        @Agent("Checks Datadog for error rate")
        String checkErrors(@V("service") String service);
    }

    interface JenkinsChecker {
        @UserMessage("Report the most recent deploy info for {{service}} in one short sentence.")
        @Agent("Checks Jenkins for recent deploys")
        String checkDeploys(@V("service") String service);
    }

    static void runParallel() {
        System.out.println("\n=== PART B: parallel workflow ===");

        // Each parallel branch gets its OWN scripted model so their
        // responses are deterministic regardless of which thread the
        // executor happens to run first.
        ChatModel datadogModel = ScriptedChatModel.of(
                AiMessage.from("checkout-api: 6.8% error rate, well above the 0.3% baseline."));
        ChatModel jenkinsModel = ScriptedChatModel.of(
                AiMessage.from("checkout-api: build #482 deployed at 14:02 UTC."));

        DatadogChecker datadogChecker = AgenticServices.agentBuilder(DatadogChecker.class)
                .chatModel(datadogModel)
                .outputKey("errorReport")
                .build();

        JenkinsChecker jenkinsChecker = AgenticServices.agentBuilder(JenkinsChecker.class)
                .chatModel(jenkinsModel)
                .outputKey("deployReport")
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            UntypedAgent parallelChecks = AgenticServices.parallelBuilder()
                    .subAgents(datadogChecker, jenkinsChecker)
                    .executor(pool)
                    // Neither branch's own outputKey is what invoke() returns for a
                    // parallel workflow -- output(...) is the explicit merge step
                    // that reads both written scope variables and combines them.
                    .output(scope -> "Datadog: " + scope.readState("errorReport")
                            + " | Jenkins: " + scope.readState("deployReport"))
                    .build();

            Object combined = parallelChecks.invoke(Map.of("service", "checkout-api"));
            System.out.println("both checks ran concurrently on the executor; merged result:");
            System.out.println(combined);
        } finally {
            pool.shutdown();
        }
    }

    public static void main(String[] args) {
        runSequential();
        runParallel();

        System.out.println(
                "\nCompare to LangGraph: sequential here is a straight-line StateGraph "
                        + "(lesson 3 in the Python course); parallel here is Send-based "
                        + "fan-out/fan-in (lesson 4). Same shape, different syntax.");
    }
}
