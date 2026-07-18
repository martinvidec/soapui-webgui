package de.soapuiweb.storage;

import java.util.List;

/**
 * Sidecar-Metadaten eines Projekts ({@code meta.json}). Liegt bewusst NICHT in
 * der Projekt-XML, damit der Download byte-identisch bleibt (Spezifikation 2.1).
 */
public record ProjectMeta(
        String name,
        String uploadedBy,
        long uploadedAtEpochMs,
        long lastModifiedAtEpochMs,
        List<String> autostartMockIds) {

    public ProjectMeta {
        autostartMockIds = autostartMockIds == null ? List.of() : List.copyOf(autostartMockIds);
    }

    public ProjectMeta touched(long nowEpochMs) {
        return new ProjectMeta(name, uploadedBy, uploadedAtEpochMs, nowEpochMs, autostartMockIds);
    }
}
