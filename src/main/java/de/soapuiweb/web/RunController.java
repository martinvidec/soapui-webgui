package de.soapuiweb.web;

import de.soapuiweb.service.EventStreamService;
import de.soapuiweb.service.ProjectService;
import de.soapuiweb.service.TestRunManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Testausführung (FA-33/34/36): Läufe starten (TestCase/TestSuite/TestStep),
 * Live-Events per SSE, Ergebnis-Fragment mit Polling, Abbruch.
 * Ausführen erfordert keine Edit-Sperre (wie Senden im Request-Editor).
 */
@Controller
public class RunController {

    private final TestRunManager runManager;
    private final EventStreamService events;
    private final ProjectService projectService;
    private final TestPanelModel testPanels;

    public RunController(TestRunManager runManager, EventStreamService events,
                         ProjectService projectService, TestPanelModel testPanels) {
        this.runManager = runManager;
        this.events = events;
        this.projectService = projectService;
        this.testPanels = testPanels;
    }

    @PostMapping("/projects/{id}/testcases/{caseId}/run")
    public String runCase(@PathVariable String id, @PathVariable String caseId,
                          Authentication auth, Model model) {
        try {
            model.addAttribute("run", runManager.startTestCase(id, caseId));
            return "project/run-panel :: panel";
        } catch (RuntimeException e) {
            testPanels.fillCase(id, caseId, e.getMessage(), null, auth.getName(), model);
            return "project/case-panel :: panel";
        }
    }

    @PostMapping("/projects/{id}/testsuites/{suiteId}/run")
    public String runSuite(@PathVariable String id, @PathVariable String suiteId,
                           Authentication auth, Model model) {
        try {
            model.addAttribute("run", runManager.startTestSuite(id, suiteId));
            return "project/run-panel :: panel";
        } catch (RuntimeException e) {
            testPanels.fillSuite(id, suiteId, e.getMessage(), null, auth.getName(), model);
            return "project/suite-panel :: panel";
        }
    }

    @PostMapping("/projects/{id}/teststeps/{stepId}/run")
    public String runStep(@PathVariable String id, @PathVariable String stepId,
                          Authentication auth, Model model) {
        try {
            model.addAttribute("run", runManager.startTestStep(id, stepId));
            return "project/run-panel :: panel";
        } catch (RuntimeException e) {
            testPanels.fillStep(id, stepId, e.getMessage(), null, auth.getName(), model);
            return "project/step-panel :: panel";
        }
    }

    @GetMapping("/runs/{runId}")
    public String runPanel(@PathVariable String runId, Model model) {
        model.addAttribute("run", runManager.require(runId));
        return "project/run-panel :: panel";
    }

    @GetMapping("/runs/{runId}/results")
    public String results(@PathVariable String runId, Model model) {
        model.addAttribute("run", runManager.require(runId));
        return "project/run-panel :: results";
    }

    @PostMapping("/runs/{runId}/cancel")
    public String cancel(@PathVariable String runId, Model model) {
        runManager.cancel(runId);
        model.addAttribute("run", runManager.require(runId));
        return "project/run-panel :: panel";
    }

    /** Live-Events (FA-33); Last-Event-ID macht Reconnects verlustfrei. */
    @GetMapping("/runs/{runId}/events")
    public SseEmitter runEvents(@PathVariable String runId,
                                @RequestHeader(value = "Last-Event-ID", defaultValue = "0")
                                long lastEventId) {
        runManager.require(runId);
        return events.subscribe(runId, lastEventId);
    }
}
