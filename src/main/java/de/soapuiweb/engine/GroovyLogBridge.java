package de.soapuiweb.engine;

import de.soapuiweb.service.EventStreamService;
import de.soapuiweb.service.TestRunManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Skript-Log-Bridge (FA-41): Groovy-Skripte loggen über den SoapUI-Logger
 * "groovy.log". Ein dedizierter Log4j2-Appender ordnet die Ausgaben über den
 * Runner-Thread dem laufenden Testlauf zu und speist sie in dessen SSE-Feed.
 */
@Component
public class GroovyLogBridge implements SmartLifecycle {

    private static final String GROOVY_LOGGER = "groovy.log";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TestRunManager runManager;
    private final EventStreamService events;
    private final String appenderName = "groovy-web-bridge-" + java.util.UUID.randomUUID();
    private volatile boolean running;
    private AbstractAppender appender;

    public GroovyLogBridge(TestRunManager runManager, EventStreamService events) {
        this.runManager = runManager;
        this.events = events;
    }

    @Override
    public void start() {
        appender = new AbstractAppender(appenderName, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent logEvent) {
                String runId = runManager.runIdForCurrentThread();
                if (runId == null) {
                    return;
                }
                events.append(runId, "<div class=\"log-entry log-groovy\">"
                        + "<span class=\"log-time\">" + TIME_FORMAT.format(Instant.now()) + "</span> "
                        + "<span class=\"node-type\">Skript</span> "
                        + HtmlUtils.htmlEscape(logEvent.getMessage().getFormattedMessage())
                        + "</div>");
            }
        };
        appender.start();
        // Dedizierte LoggerConfig für groovy.log; existiert sie bereits (weitere
        // Spring-Kontexte im selben JVM, z. B. Tests), nur den Appender anhängen —
        // ein addLogger würde die Config ERSETZEN und fremde Bridges abhängen
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig existing = configuration.getLoggerConfig(GROOVY_LOGGER);
        if (GROOVY_LOGGER.equals(existing.getName())) {
            existing.addAppender(appender, Level.INFO, null);
        } else {
            LoggerConfig loggerConfig = new LoggerConfig(GROOVY_LOGGER, Level.INFO, true);
            loggerConfig.addAppender(appender, Level.INFO, null);
            configuration.addLogger(GROOVY_LOGGER, loggerConfig);
        }
        context.updateLoggers();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration().getLoggerConfig(GROOVY_LOGGER);
        loggerConfig.removeAppender(appenderName);
        context.updateLoggers();
        if (appender != null) {
            appender.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // nach der Engine (MIN+100)
        return Integer.MIN_VALUE + 150;
    }
}
