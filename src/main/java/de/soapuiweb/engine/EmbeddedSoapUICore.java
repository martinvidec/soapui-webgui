package de.soapuiweb.engine;

import com.eviware.soapui.DefaultSoapUICore;
import org.apache.logging.log4j.LogManager;

/**
 * Headless-Variante des SoapUI-Cores für den eingebetteten Betrieb.
 *
 * {@link DefaultSoapUICore#initLog()} ersetzt die Log4j2-Konfiguration der JVM
 * durch die SoapUI-eigene — damit verschwänden alle App-Appender ab Engine-Init
 * (beobachtet: Spring-Logs enden nach "All plugins loaded"). Logging wird
 * ausschließlich über die log4j2.xml der Anwendung konfiguriert, daher No-Op.
 */
class EmbeddedSoapUICore extends DefaultSoapUICore {

    EmbeddedSoapUICore(String root, String settingsFile) {
        super(root, settingsFile);
    }

    @Override
    protected void initLog() {
        // Keine Log4j2-Rekonfiguration — aber das statische Logger-Feld muss
        // gesetzt werden, sonst NPE in initSettings() (Feld wird sonst hier befüllt)
        DefaultSoapUICore.log = LogManager.getLogger(DefaultSoapUICore.class);
    }
}
