package de.soapuiweb.engine;

import com.eviware.soapui.SoapUI;
import de.soapuiweb.config.AppProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initialisiert die eingebettete SoapUI-Engine headless beim App-Start und
 * fährt sie beim Stop sauber herunter (Spezifikation 2.1, Engine-Einbettung).
 *
 * Die Engine hält JVM-weiten statischen Zustand (Settings, MockEngine) —
 * Init und Shutdown dürfen genau einmal pro JVM laufen.
 */
@Component
public class SoapUiEngineLifecycle implements SmartLifecycle {

    private static final Logger log = LogManager.getLogger(SoapUiEngineLifecycle.class);

    private final AppProperties properties;
    private volatile boolean running;

    public SoapUiEngineLifecycle(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        System.setProperty("java.awt.headless", "true");
        Path engineDir = properties.dataDir().resolve("engine");
        try {
            Files.createDirectories(engineDir);
        } catch (IOException e) {
            throw new IllegalStateException("Engine-Verzeichnis nicht anlegbar: " + engineDir, e);
        }
        Path settingsFile = engineDir.resolve("soapui-settings.xml");
        ensureSettingsFile(settingsFile);
        SoapUI.setSoapUICore(new EmbeddedSoapUICore(engineDir.toString(), settingsFile.toString()), true);
        running = true;
        log.info("SoapUI-Engine {} initialisiert (headless), Settings: {}", SoapUI.SOAPUI_VERSION, settingsFile);
    }

    /**
     * DefaultSoapUICore fällt auf {@code user.home/soapui-settings.xml} zurück,
     * wenn die übergebene Settings-Datei nicht existiert — deshalb wird sie hier
     * vorab mit einem leeren Settings-Dokument angelegt.
     */
    private static void ensureSettingsFile(Path settingsFile) {
        if (Files.exists(settingsFile)) {
            return;
        }
        try {
            Files.writeString(settingsFile,
                    "<con:soapui-settings xmlns:con=\"http://eviware.com/soapui/config\"/>");
        } catch (IOException e) {
            throw new IllegalStateException("Settings-Datei nicht anlegbar: " + settingsFile, e);
        }
    }

    @Override
    public void stop() {
        running = false;
        SoapUI.shutdown();
        log.info("SoapUI-Engine heruntergefahren");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Vor dem Web-Container starten, nach ihm stoppen
        return Integer.MIN_VALUE + 100;
    }
}
