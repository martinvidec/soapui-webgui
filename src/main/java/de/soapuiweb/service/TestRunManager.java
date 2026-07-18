package de.soapuiweb.service;

import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.panels.support.MockTestRunner;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.MessageExchange;
import com.eviware.soapui.model.support.TestRunListenerAdapter;
import com.eviware.soapui.model.testsuite.TestCaseRunContext;
import com.eviware.soapui.model.testsuite.TestCaseRunner;
import com.eviware.soapui.model.testsuite.TestRunner;
import com.eviware.soapui.model.testsuite.TestStep;
import com.eviware.soapui.model.testsuite.TestStepResult;
import com.eviware.soapui.support.types.StringToObjectMap;
import de.soapuiweb.engine.ModelItems;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Testausführung (FA-33/34/36, NFA-11): Run-Registry mit Live-Events (SSE),
 * Step-Ergebnissen inkl. Request/Response, Abbruch. Pro Projekt läuft maximal
 * ein Lauf; Läufe verschiedener Projekte laufen parallel.
 */
@Service
public class TestRunManager {

    public record StepResultRow(String stepName, String status, long timeTakenMs,
                                List<String> messages, String requestContent,
                                String responseContent) {
    }

    public static final class ActiveRun {
        private final String runId = UUID.randomUUID().toString();
        private final String projectId;
        private final String targetName;
        private final String kind;
        private final long startedAtEpochMs = Instant.now().toEpochMilli();
        private final CopyOnWriteArrayList<StepResultRow> results = new CopyOnWriteArrayList<>();
        private final AtomicReference<TestRunner> runner = new AtomicReference<>();
        private volatile String status = "RUNNING";
        private volatile String reason;
        private volatile long timeTakenMs;

        ActiveRun(String projectId, String targetName, String kind) {
            this.projectId = projectId;
            this.targetName = targetName;
            this.kind = kind;
        }

        public String runId() {
            return runId;
        }

        public String projectId() {
            return projectId;
        }

        public String targetName() {
            return targetName;
        }

        public String kind() {
            return kind;
        }

        public String status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        public long timeTakenMs() {
            return timeTakenMs;
        }

        public List<StepResultRow> results() {
            return List.copyOf(results);
        }

        public boolean isRunning() {
            return "RUNNING".equals(status);
        }
    }

    private static final Logger log = LogManager.getLogger(TestRunManager.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_KEPT_RUNS = 100;
    private static final int MAX_CONTENT_LENGTH = 100_000;

    private final ProjectService projectService;
    private final EventStreamService events;
    private final Map<String, ActiveRun> runs = new ConcurrentHashMap<>();
    /** Runner-Thread → runId: Zuordnung für die Groovy-Log-Bridge (FA-41). */
    private final Map<Thread, String> runThreads = new ConcurrentHashMap<>();
    private final Deque<String> runOrder = new ArrayDeque<>();
    private final ExecutorService stepRunExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "step-run");
        thread.setDaemon(true);
        return thread;
    });

    public TestRunManager(ProjectService projectService, EventStreamService events) {
        this.projectService = projectService;
        this.events = events;
    }

    public synchronized ActiveRun startTestCase(String projectId, String caseId) {
        WsdlTestCase testCase = resolve(projectId, caseId, WsdlTestCase.class);
        ensureNoActiveRun(projectId);
        ActiveRun run = register(new ActiveRun(projectId, testCase.getName(), "TestCase"));
        event(run, "Lauf gestartet: TestCase '" + testCase.getName() + "'");
        TestRunListenerAdapter listener = caseListener(run, true);
        testCase.addTestRunListener(listener);
        try {
            run.runner.set(testCase.run(new StringToObjectMap(), true));
        } catch (RuntimeException e) {
            testCase.removeTestRunListener(listener);
            finish(run, "FAILED", e.getMessage(), 0);
            throw e;
        }
        return run;
    }

    public synchronized ActiveRun startTestSuite(String projectId, String suiteId) {
        WsdlTestSuite suite = resolve(projectId, suiteId, WsdlTestSuite.class);
        ensureNoActiveRun(projectId);
        ActiveRun run = register(new ActiveRun(projectId, suite.getName(), "TestSuite"));
        event(run, "Lauf gestartet: TestSuite '" + suite.getName() + "'");
        List<WsdlTestCase> cases = suite.getTestCaseList().stream()
                .map(WsdlTestCase.class::cast).toList();
        TestRunListenerAdapter caseListener = caseListener(run, false);
        cases.forEach(c -> c.addTestRunListener(caseListener));
        // Suite-Lauf in eigenem Thread überwachen: Ende erkennen + Listener abräumen
        stepRunExecutor.submit(() -> {
            try {
                var suiteRunner = suite.run(new StringToObjectMap(), true);
                run.runner.set(suiteRunner);
                suiteRunner.waitUntilFinished();
                finish(run, suiteRunner.getStatus().name(), suiteRunner.getReason(),
                        suiteRunner.getTimeTaken());
            } catch (Exception e) {
                finish(run, "FAILED", e.getMessage(), 0);
            } finally {
                cases.forEach(c -> c.removeTestRunListener(caseListener));
            }
        });
        return run;
    }

    public synchronized ActiveRun startTestStep(String projectId, String stepId) {
        WsdlTestStep step = resolve(projectId, stepId, WsdlTestStep.class);
        ensureNoActiveRun(projectId);
        ActiveRun run = register(new ActiveRun(projectId, step.getName(), "TestStep"));
        event(run, "Lauf gestartet: TestStep '" + step.getName() + "'");
        stepRunExecutor.submit(() -> {
            runThreads.put(Thread.currentThread(), run.runId());
            try {
                MockTestRunner runner = new MockTestRunner(step.getTestCase());
                run.runner.set(runner);
                TestStepResult result = runner.runTestStep(step);
                addResult(run, result);
                finish(run, result == null ? "FAILED" : switch (result.getStatus()) {
                    case OK -> "FINISHED";
                    case CANCELED -> "CANCELED";
                    default -> "FAILED";
                }, null, result == null ? 0 : result.getTimeTaken());
            } catch (Exception e) {
                finish(run, "FAILED", e.getMessage(), 0);
            } finally {
                runThreads.remove(Thread.currentThread());
            }
        });
        return run;
    }

    /**
     * runId des Laufs im aktuellen Thread (Groovy-Log-Bridge). Fallback: Die
     * Engine führt Setup-Skripte VOR den beforeRun-Listenern aus (Thread noch
     * nicht registriert) — läuft genau ein Lauf, wird ihm zugeordnet; bei
     * mehreren wird verworfen statt falsch zugeordnet.
     */
    public String runIdForCurrentThread() {
        String runId = runThreads.get(Thread.currentThread());
        if (runId != null) {
            return runId;
        }
        List<ActiveRun> active = runs.values().stream().filter(ActiveRun::isRunning).toList();
        return active.size() == 1 ? active.get(0).runId() : null;
    }

    public void cancel(String runId) {
        ActiveRun run = require(runId);
        TestRunner runner = run.runner.get();
        if (run.isRunning() && runner != null) {
            runner.cancel("Abbruch durch Nutzer");
            event(run, "Abbruch angefordert …");
        }
    }

    public ActiveRun require(String runId) {
        ActiveRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Unbekannter Lauf: " + runId);
        }
        return run;
    }

    public Optional<ActiveRun> runningRunOf(String projectId) {
        return runs.values().stream()
                .filter(r -> r.projectId().equals(projectId) && r.isRunning())
                .findFirst();
    }

    private TestRunListenerAdapter caseListener(ActiveRun run, boolean finishOnAfterRun) {
        return new TestRunListenerAdapter() {
            @Override
            public void beforeRun(TestCaseRunner runner, TestCaseRunContext context) {
                // läuft im Runner-Thread — Zuordnung für die Groovy-Log-Bridge
                runThreads.put(Thread.currentThread(), run.runId());
            }

            @Override
            public void beforeStep(TestCaseRunner runner, TestCaseRunContext context,
                                   TestStep step) {
                event(run, "▶ " + step.getName());
            }

            @Override
            public void afterStep(TestCaseRunner runner, TestCaseRunContext context,
                                  TestStepResult result) {
                addResult(run, result);
            }

            @Override
            public void afterRun(TestCaseRunner runner, TestCaseRunContext context) {
                runThreads.remove(Thread.currentThread());
                if (finishOnAfterRun) {
                    finish(run, runner.getStatus().name(), runner.getReason(),
                            runner.getTimeTaken());
                    runner.getTestCase().removeTestRunListener(this);
                } else {
                    event(run, "TestCase '" + runner.getTestCase().getName() + "' beendet: "
                            + runner.getStatus().name());
                }
            }
        };
    }

    private void addResult(ActiveRun run, TestStepResult result) {
        if (result == null) {
            return;
        }
        String requestContent = null;
        String responseContent = null;
        if (result instanceof MessageExchange exchange) {
            requestContent = trim(exchange.getRequestContent());
            responseContent = trim(exchange.getResponseContent());
        }
        StepResultRow row = new StepResultRow(
                result.getTestStep().getName(),
                result.getStatus().name(),
                result.getTimeTaken(),
                result.getMessages() == null ? List.of() : List.of(result.getMessages()),
                requestContent, responseContent);
        run.results.add(row);
        event(run, statusSymbol(row.status()) + " " + row.stepName() + " — " + row.status()
                + " (" + row.timeTakenMs() + " ms)"
                + (row.messages().isEmpty() ? "" : ": " + row.messages().get(0)));
    }

    private void finish(ActiveRun run, String status, String reason, long timeTaken) {
        run.status = status;
        run.reason = reason;
        run.timeTakenMs = timeTaken;
        event(run, "Lauf beendet: " + status
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        log.info("Testlauf {} ({}) beendet: {}", run.runId(), run.targetName(), status);
    }

    private ActiveRun register(ActiveRun run) {
        runs.put(run.runId(), run);
        synchronized (runOrder) {
            runOrder.addLast(run.runId());
            while (runOrder.size() > MAX_KEPT_RUNS) {
                String evicted = runOrder.pollFirst();
                runs.remove(evicted);
                events.clear(evicted);
            }
        }
        return run;
    }

    private void ensureNoActiveRun(String projectId) {
        runningRunOf(projectId).ifPresent(active -> {
            throw new IllegalStateException("Im Projekt läuft bereits ein Testlauf ('"
                    + active.targetName() + "') — bitte warten oder abbrechen");
        });
    }

    private void event(ActiveRun run, String text) {
        events.append(run.runId(), "<div class=\"log-entry\"><span class=\"log-time\">"
                + TIME_FORMAT.format(Instant.now()) + "</span> "
                + HtmlUtils.htmlEscape(text) + "</div>");
    }

    private static String statusSymbol(String status) {
        return switch (status) {
            case "OK", "FINISHED" -> "✔";
            case "CANCELED" -> "◼";
            default -> "✘";
        };
    }

    private static String trim(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > MAX_CONTENT_LENGTH
                ? content.substring(0, MAX_CONTENT_LENGTH) + "\n… (gekürzt)" : content;
    }

    private <T> T resolve(String projectId, String itemId, Class<T> type) {
        ModelItem item = ModelItems.findById(projectService.require(projectId).project(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Element: " + itemId));
        if (!type.isInstance(item)) {
            throw new IllegalArgumentException("Element ist kein " + type.getSimpleName());
        }
        return type.cast(item);
    }
}
