# Spezifikation: WebGUI für SoapUI-Projekte

## 1. Übersicht

Umsetzung der Phase-1-Anforderungen aus der [Anforderungsanalyse](03-anforderungsanalyse-soapui-webgui.md) als **eine Spring-Boot-Anwendung** (ein Maven-Modul), die die SoapUI-OS-Engine 5.10.0 headless einbettet (Machbarkeit verifiziert, siehe [Ist-Analyse](02-ist-analyse-soapui-webgui.md), Abschnitt 6). UI serverseitig gerendert (Thymeleaf + HTMX), Projektablage im Dateisystem, Auslieferung als Fat-JAR für JDK 21.

## 2. Technisches Design

### 2.1 Architektur

**Technologie-Baseline:** Java 21, Spring Boot 3.x (aktuellstes Release), Tomcat (Starter-Default), Thymeleaf, HTMX 2.x + Alpine.js 3 + CodeMirror 6 (alle als lokale statische Assets im JAR — kein CDN), SoapUI-Engine `com.smartbear.soapui:soapui:5.10.0`, Maven.

**Schichten (Pakete unter `de.soapuiweb`):**

```
web/        Controller: Thymeleaf-Seiten, HTMX-Fragmente, SSE-Endpunkte, Login
service/    ProjectService, LockService, MockManager, TestRunManager, UserService
engine/     Engine-Adapter: Bootstrap (headless Init/Shutdown), Wrapper um
            WsdlProject/MockRunner/TestCaseRunner, Registry-Zugriffe (Steps, Assertions)
storage/    Dateisystem-Ablage: ProjectStore (atomares Speichern), MetaStore, UserStore
config/     Spring-Konfiguration, Security, Properties (Typed @ConfigurationProperties)
```

Grundregel: **Kein Controller fasst die SoapUI-API direkt an** — alles läuft über `engine/`-Adapter. Das kapselt den statischen Engine-Zustand, erzwingt die Nebenläufigkeitsregeln und hält die Engine-Version austauschbar.

**Engine-Einbettung:**

- Beim App-Start einmalige Headless-Initialisierung (`DefaultSoapUICore`, `java.awt.headless=true`), beim Shutdown `SoapUI.shutdown()` plus Stoppen aller MockRunner (Spring `SmartLifecycle`).
- Dependency-Ausschlüsse im POM: alle `org.openjfx:*` (JavaFX, −171 MB) sowie Desktop-only-UI-Libs (JGoodies, RSyntaxTextArea), sofern der Integrationstest (Spike-Szenario) mit Ausschlüssen grün bleibt — das wird in Schritt 1 verifiziert; Ausschlüsse, die Laufzeitfehler verursachen, werden zurückgenommen und dokumentiert.
- **Logging-Entscheidung:** Die Anwendung nutzt durchgehend **Log4j2** (`spring-boot-starter-log4j2`, Logback-Starter ausgeschlossen). Begründung: SoapUI bringt `log4j-core` + `log4j-slf4j2-impl` zwingend mit; Konvergenz auf Log4j2 vermeidet doppelte SLF4J-Bindings und führt App- und Engine-Logs in einer Konfiguration zusammen.
- Dependency-Overrides zentral in `dependencyManagement` (u. a. XmlBeans `3.1.1-sb-fixed` fixieren).

**Nebenläufigkeitsmodell** (setzt NFA-11 um):

- Pro Projekt ein `ProjectHandle` mit `ReentrantReadWriteLock`: Lesen (Baum rendern, Download) parallel; jede Mutation und jedes Speichern exklusiv.
- Darüber die fachliche **Edit-Sperre** (FA-06): in-memory, Inhaber + Ablaufzeit (Default 30 min, per Aktivität verlängert); Mutations-Endpunkte prüfen die Sperre. In-memory heißt: App-Restart löst alle Sperren — akzeptiert und dokumentiert.
- Mock-Start/-Stop und Testläufe verschiedener Projekte laufen parallel; pro Projekt wird höchstens ein Testlauf gleichzeitig zugelassen (Queue mit Ablehnung + Meldung).

**Mock-Lifecycle (FA-11/14/15):**

- `MockManager` führt die Registry `mockId → MockRunner` und den reservierten Portbereich (`app.mock.port-range`, z. B. 18000–18999). Start prüft: Port im Bereich? Port frei? Sonst Fehlermeldung mit Verursacher.
- Autostart-Flags liegen **nicht** in der Projektdatei (Roundtrip-Garantie!), sondern im Meta-Sidecar (siehe 2.2). Beim App-Start: Projekte scannen → geflaggte Mocks starten; Fehler einzelner Mocks (z. B. Port belegt) blockieren den App-Start nicht, sondern landen als Warnung in Log + UI.
- Die Mock-Listener sind SoapUI-eigene Jetty-6-Instanzen auf eigenen Ports — sie laufen **nicht** durch Tomcat/Spring Security (deshalb sind Mock-Endpunkte ohne Login erreichbar, FA-50).

**Live-Events (FA-12/33/41):** Server-Sent Events über Spring `SseEmitter`.

- Quellen: `TestRunListener` (Step-Start/-Ende, Status), Mock-`onMockResult` (Request-Log), Log4j-Appender für Groovy-`log`-Ausgaben (gefiltert pro Lauf).
- Pro Lauf/Mock ein Ring-Puffer (letzte 500 Events) im Speicher; SSE-Reconnect (`Last-Event-ID`) spielt aus dem Puffer nach. HTMX-SSE-Extension konsumiert die Events direkt in die UI.

**Adressierung von Modellelementen:** SoapUI vergibt für jedes `ModelItem` eine persistente UUID (`ModelItem.getId()`, in der Projektdatei gespeichert). Alle URLs/Formulare adressieren Elemente über diese IDs — stabil über Reloads und eindeutig im Projekt.

### 2.2 Datenmodell

Keine Datenbank. Dateisystem + In-Memory-Laufzeitmodell:

```
${app.data-dir}/
  projects/<projectId>/
    project.xml     # Original-SoapUI-Datei (Single Source of Truth, FA-01/02)
    meta.json       # Sidecar: Anzeigename, Upload-/Änderungszeit, Uploader,
                    # autostartMockIds: [ModelItem-UUIDs]
  users.json        # Nutzer: login, bcrypt-Hash, Rolle (ADMIN|USER), aktiv-Flag
```

- `projectId` = beim Upload vergebene UUID (Verzeichnisname) — unabhängig vom Projektnamen, erlaubt gleichnamige Projekte.
- **Atomares Speichern (NFA-05):** Schreiben nach `project.xml.tmp` → `Files.move(..., ATOMIC_MOVE)`. Gilt für `project.xml`, `meta.json`, `users.json`.
- In-Memory: `ProjectHandle` (geladenes `WsdlProject`, RW-Lock, Edit-Sperre), `MockManager`-Registry, `TestRunManager`-Registry (`runId → Runner, Status, Ring-Puffer`). Projekte werden beim App-Start geladen (Autostart braucht sie ohnehin); bei späterem Bedarf lazy-loading nachrüstbar.

### 2.3 Schnittstellen

Alle Endpunkte hinter Spring-Security-Form-Login (Ausnahme: `/login`, statische Assets). HTMX-Fragmente und JSON teilen sich die Controller (Content-Negotiation nur wo sinnvoll; primär HTML-Fragmente).

| Bereich | Endpunkte (Auszug) |
|---|---|
| Projekte | `GET /` Liste · `POST /projects` Upload (Multipart) · `GET /projects/{id}` Arbeitsansicht (Baum + Detail) · `GET /projects/{id}/download` · `DELETE /projects/{id}` |
| Sperre | `POST /projects/{id}/lock` · `DELETE /projects/{id}/lock` · `DELETE /projects/{id}/lock?force=true` (ADMIN) |
| Baum/Detail | `GET /projects/{id}/tree` (Fragment) · `GET /projects/{id}/items/{itemId}` Detail-Panel je Elementtyp (Request-Editor, TestCase, MockService, …) |
| Mocks | `GET /mocks` globale Übersicht · `POST /projects/{id}/mocks/{itemId}/start` · `.../stop` · `PUT .../autostart` · `GET .../log` (SSE) · CRUD MockOperationen/-Responses/Dispatch |
| Requests | `PUT /projects/{id}/requests/{itemId}` Content/Header/Endpoint · `POST .../submit` → Response-Panel · Endpoint-CRUD am Interface |
| Tests | CRUD Suites/Cases/Steps/Properties · `GET /assertion-types?stepId=` (Engine-Registry) · Assertion-CRUD · `POST /projects/{id}/testcases/{itemId}/run` → `runId` · `GET /runs/{runId}/events` (SSE) · `GET /runs/{runId}` Ergebnis · `POST /runs/{runId}/cancel` |
| Auth/Admin | `GET/POST /login` · `POST /logout` · `/admin/users` CRUD (ADMIN) · `POST /profile/password` |
| Betrieb | `GET /actuator/health` (ohne Auth) · `GET /actuator/info` |

**UI-Layout** (eine Arbeitsansicht pro Projekt, angelehnt an den Desktop-Client): Projektbaum links (HTMX-Lazy-Loading pro Ebene), Detail-Panel rechts (Editor je Elementtyp), Log-/Ergebnisleiste unten (SSE-Feed). Read-only-Modus ohne Sperre: Editoren disabled, Hinweisbanner mit „Sperre übernehmen".

## 3. Implementierungsplan

### 3.1 Änderungen pro Komponente

| Komponente | Änderung | Aufwand |
|---|---|---|
| Build/Grundgerüst | POM (SmartBear-Repo, Exclusions, Log4j2), Engine-Bootstrap, Integrationstest Spike-Szenario | Mittel |
| Security/Nutzer | Form-Login, `users.json`-Store (bcrypt), Admin-UI, Rollen | Mittel |
| Projektverwaltung | Upload/Download/Liste/Löschen, ProjectStore (atomar), Meta-Sidecar | Mittel |
| Baum + Sperre | Tree-Rendering (alle Elementtypen), LockService, Read-only-Modus | Mittel |
| Mock-Betrieb | MockManager, Start/Stop, Portprüfung, globale Übersicht, SSE-Log, Autostart, Shutdown-Hook | Groß |
| Mock-Editing | CRUD Operationen/Responses, Dispatch-Konfiguration | Mittel |
| Request-Editor | CodeMirror-Integration, Content/Header/Endpoint, Submit + Response-Panel | Groß |
| Test-Verwaltung | CRUD Suites/Cases/Steps (Registry-basiert), Properties-CRUD | Groß |
| Assertions | Generische Registry-Anbindung, Konfigurationsdialoge, Ergebnisanzeige | Mittel |
| Testausführung | TestRunManager, SSE-Fortschritt, Ergebnisansicht, Cancel | Groß |
| Groovy | CodeMirror-Groovy-Modus an allen Skript-Stellen, Log-Bridge | Mittel |
| Betrieb | Fat-JAR-Optimierung (< 150 MB), systemd-Unit, Betriebshandbuch, Actuator | Klein |

### 3.2 Reihenfolge der Implementierung

1. **Grundgerüst & Engine-Einbettung** — Spring-Boot-Projekt, POM mit SmartBear-Repo/Exclusions/Log4j2, Engine-Bootstrap (Init/Shutdown), Actuator; Integrationstest = Spike-Szenario (Mock starten, Request, Testlauf) gegen den *reduzierten* Classpath. Danach steht die Basis beweisbar.
2. **Login & Nutzerverwaltung** — Spring Security, users.json, Admin-UI, Rollen (FA-50–52).
3. **Projektverwaltung** — Upload/Download/Liste/Löschen + atomare Ablage + Sidecar (FA-01–04).
4. **Projektbaum & Sperre** — Baum aller Elementtypen, LockService, Read-only-Modus (FA-05/06).
5. **Mock-Betrieb** — Start/Stop, Portbereich/-konflikte, globale Übersicht, SSE-Request-Log, **Autostart**, Shutdown-Hook (FA-10–12, 14–16).
6. **Mock-Editing** — Operationen/Responses/Dispatch (FA-13).
7. **Request-Editor** — CodeMirror, Bearbeiten, Senden, Response-Panel, Endpoints (FA-20–23).
8. **Test-Verwaltung & Properties** — CRUD Suites/Cases/Steps, Properties-Ebenen (FA-30/31/35).
9. **Assertions** — generische Registry-Anbindung inkl. UI (FA-32).
10. **Testausführung** — Läufe mit SSE-Fortschritt, Ergebnissen, Abbruch (FA-33/34/36).
11. **Groovy-Editoren & Skript-Log** — alle Skript-Stellen, Log-Bridge (FA-40/41).
12. **Betrieb & Deployment** — JAR-Größe, systemd, Betriebshandbuch (dedizierter OS-User, Firewall-Portbereich), Restlücken NFA (Abschnitt 5).

Jeder Schritt endet nutzbar und wird einzeln als GitHub-Issue geführt; ab Schritt 5 existiert der primäre Servermehrwert (dauerhafte Mocks).

## 4. Testplan

- **Unit-Tests:** LockService (Timeout, Force-Unlock), ProjectStore (atomares Speichern, Fehlerfälle), MockManager-Portlogik, UserStore. Ohne Engine, schnell.
- **Integrationstests (Engine, headless):** das Spike-Szenario als dauerhafter Test in Schritt 1; Upload→Baum→Download-Roundtrip (byte-identisch ohne Edits); Mock-Lifecycle über echten HTTP-Request; Autostart über Kontext-Neustart im Test; Testlauf mit fehlschlagender Assertion; paralleler Lauf in zwei Projekten (NFA-11).
- **E2E-Tests (UI):** Playwright gegen die laufende App, ein Test-Set pro UI-Bereich (Login, Projektliste/Upload, Baum/Sperre, Mock-Panel, Request-Editor, Testlauf), in den Maven-Build integriert.
- **Manuelle Tests:** Referenzprojekt im SoapUI-Desktop-Client öffnen (Roundtrip-Nachweis, Akzeptanzkriterium 2); Mock-Zugriff von einem zweiten Rechner (Firewall/Portbereich).

## 5. Migration / Deployment

- **Build:** `mvn package` → ein Fat-JAR. Voraussetzung: Zugriff auf den SmartBear-Nexus (Proxy/Mirror empfohlen, siehe Ist-Analyse).
- **Zielserver:** JDK 21, dedizierter OS-Benutzer `soapui-web` mit minimalen Rechten (NFA-02: Groovy = Code-Ausführung), Datenverzeichnis `/var/lib/soapui-webgui`, systemd-Unit (Restart=on-failure) — Beispiel-Unit und `application.yml`-Referenz (`app.data-dir`, `app.mock.port-range`, Session-Timeout) liegen dem Repo bei (Schritt 12).
- **Firewall:** App-Port (443/8080 hinter Reverse-Proxy, TLS am Proxy) + Mock-Portbereich freigeben.
- **Erststart:** legt `users.json` mit Admin-Initialpasswort an (wird geloggt und muss beim ersten Login geändert werden).
- **Backup:** `${app.data-dir}` sichern genügt (Projekte + Meta + Nutzer).
- Keine Migration nötig (Greenfield).

## 6. Referenzen

- [Konzeptdokument](01-konzept-soapui-webgui.md)
- [Ist-Analyse](02-ist-analyse-soapui-webgui.md) — inkl. Spike (`spike/`) und Dependency-Tree
- [Anforderungsanalyse](03-anforderungsanalyse-soapui-webgui.md)
