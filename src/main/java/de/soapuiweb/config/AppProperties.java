package de.soapuiweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Zentrale Anwendungskonfiguration (Spezifikation 2.2/2.3).
 *
 * @param dataDir   Wurzelverzeichnis für Projekte, Nutzer und Engine-Settings
 * @param mock      Portbereich, aus dem MockServices Ports binden dürfen
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Path dataDir, MockProperties mock) {

    public AppProperties {
        if (dataDir == null) {
            dataDir = Path.of("data");
        }
        if (mock == null) {
            mock = new MockProperties(18000, 18999);
        }
    }

    public record MockProperties(int portMin, int portMax) {
    }
}
