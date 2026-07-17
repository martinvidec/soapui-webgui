# Anforderungsanalyse: WebGUI für SoapUI-Projekte

Abgeleitet aus [Konzept](01-konzept-soapui-webgui.md) und [Ist-Analyse](02-ist-analyse-soapui-webgui.md). Priorität „Muss" = Phase 1 (Kern), „Soll" = Phase 1 wenn ohne Terminrisiko möglich, sonst früh in Phase 2, „Kann" = spätere Phase der 1:1-Abbildung.

## 1. Funktionale Anforderungen

### Projektverwaltung

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-01 | Projekt-Upload | Muss | SoapUI-5.x-Projektdatei (XML) hochladen; Validierung durch Laden mit `WsdlProject`; fehlerhafte Dateien werden mit verständlicher Meldung abgelehnt |
| FA-02 | Projekt-Download | Muss | Serverseitig gespeicherte Projektdatei herunterladen; solange nicht editiert wurde byte-identisch zum Upload; nach Edits im SoapUI-Desktop-Client öffenbar |
| FA-03 | Projektliste | Muss | Übersicht aller Projekte mit Name, Dateigröße, Upload-/Änderungsdatum, Sperrstatus, Anzahl laufender Mocks |
| FA-04 | Projekt löschen | Muss | Löschen inkl. Stoppen laufender Mocks des Projekts; Bestätigungsdialog |
| FA-05 | Projektbaum | Muss | Navigation wie im Desktop-Client: Projekt → Interfaces → Operationen → Requests, TestSuites → TestCases → TestSteps, MockServices → MockOperationen → MockResponses |
| FA-06 | Projekt-Sperre | Muss | Editieren erfordert Sperre; andere Nutzer sehen read-only; Sperre mit Timeout und manueller Freigabe; Admin kann Sperre brechen |
| FA-07 | Projekt neu anlegen / WSDL-Import | Soll | Leeres Projekt erstellen; Interface per WSDL-URL oder -Upload importieren (`WsdlImporter`) |

### MockServices

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-10 | Mock-Übersicht | Muss | Alle MockServices (SOAP + REST) eines Projekts mit Status (läuft/gestoppt), Port, Pfad; zusätzlich globale Übersicht aller laufenden Mocks über alle Projekte |
| FA-11 | Mock starten/stoppen | Muss | Start/Stop via `MockRunner`; Port 1:1 aus der Projektdatei; sauberes Stoppen gibt den Port frei |
| FA-12 | Mock-Request-Log | Muss | Eingehende Requests eines laufenden Mocks live in der UI (Zeitstempel, Operation, Request/Response-Inhalt) |
| FA-13 | Mock-Editing | Muss | MockOperationen und MockResponses anlegen/ändern/löschen; Response-Content editieren; Dispatch-Verfahren wie SoapUI OS (Sequence, Random, XPath, Script) konfigurieren |
| FA-14 | Mock-Autostart | Muss | Als „Autostart" markierte Mocks werden nach App-Neustart automatisch wieder gestartet (dauerhafter Mock-Betrieb ist der primäre Servermehrwert) |
| FA-15 | Port-Konflikt-Handling | Muss | Belegter Port beim Start → verständliche Fehlermeldung mit Verursacher (welcher Mock/welches Projekt hält den Port) |
| FA-16 | REST-Mocks | Muss | `RestMockService` gleichwertig zu SOAP-Mocks (Start/Stop, Log, Editing) |

### Requests & Editor

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-20 | Request-Editor | Muss | XML/JSON-Editor mit Syntaxhighlighting; Request-Content, Header, Endpoint bearbeiten |
| FA-21 | Request senden | Muss | Submit über die SoapUI-Engine; Response-Anzeige mit Body (formatiert + raw), Headern, HTTP-Status, Antwortzeit, Größe |
| FA-22 | Endpoint-Verwaltung | Muss | Endpoints je Interface anzeigen/ändern/hinzufügen; Auswahl pro Request |
| FA-23 | Request-CRUD | Muss | Requests zu Operationen anlegen (inkl. Beispiel-Request aus Schema), klonen, umbenennen, löschen |
| FA-24 | Authentifizierung ausgehender Requests | Soll | Basic Auth und WS-Security-Einstellungen wie in SoapUI OS |

### Tests

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-30 | TestSuite/TestCase-Verwaltung | Muss | Anlegen, Umbenennen, Klonen, Löschen, Reihenfolge ändern; Disabled-Zustand |
| FA-31 | TestStep-Typen Phase 1 | Muss | SOAP-/REST-Request-Step, Groovy-Skript, Property-Transfer, Properties-Step, Delay; weitere Typen (Conditional Goto, Run TestCase, …) Soll |
| FA-32 | Assertions | Muss | Alle Assertion-Typen der SoapUI-OS-Engine generisch über deren Registry: anlegen, konfigurieren, löschen (u. a. Contains, XPath Match, Schema Compliance, SOAP Response, Response SLA, Script Assertion) |
| FA-33 | Testausführung | Muss | TestCase, TestSuite oder einzelnen Step ausführen; Live-Fortschritt (Step-Status, Log) in der UI |
| FA-34 | Ausführungsergebnisse | Muss | Pro Step: Status, Dauer, Assertion-Ergebnisse, gesendeter Request / empfangene Response einsehbar |
| FA-35 | Properties | Muss | Properties auf Projekt-/TestSuite-/TestCase-Ebene anzeigen/anlegen/ändern/löschen; Property-Expansion funktioniert wie im Desktop-Client |
| FA-36 | Lauf abbrechen | Muss | Laufende Testausführung abbrechen |

### Groovy & Skripte

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-40 | Skript-Editoren | Muss | Groovy-Editor mit Highlighting für: Groovy-TestSteps, Setup-/TearDown-Skripte (Projekt/Suite/Case), Script-Assertions, Mock-Skripte (Dispatch, Start/Stop) |
| FA-41 | Skript-Log | Muss | `log`-Ausgaben von Skripten erscheinen im Lauf-Log der UI |

### Benutzer & Zugriff

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-50 | Login | Muss | Form-Login (Spring Security); alle UI-/API-Endpunkte nur authentifiziert; **Ausnahme:** die Mock-Endpunkte selbst sind ohne Login erreichbar (externe Consumer) |
| FA-51 | Nutzerverwaltung | Muss | Admin legt Nutzer an/deaktiviert sie; Nutzer können eigenes Passwort ändern |
| FA-52 | Rollen | Soll | Mindestens ADMIN (Nutzerverwaltung, Sperren brechen, alle Mocks stoppen) und USER |

### Spätere Phasen (1:1-Vervollständigung)

| ID | Anforderung | Priorität | Beschreibung |
|---|---|---|---|
| FA-60 | LoadTests | Kann | SoapUI-LoadTests anzeigen/ausführen |
| FA-61 | Security-Tests | Kann | SoapUI-Security-Scans anzeigen/ausführen |
| FA-62 | JDBC-/JMS-/AMF-TestSteps | Kann | Inkl. `ext/`-Verzeichnis für Treiber (siehe Ist-Analyse) |
| FA-63 | Composite Projects | Kann | SoapUI-Composite-Projektformat (Verzeichnisstruktur) |

## 2. Nicht-funktionale Anforderungen

| ID | Anforderung | Kategorie | Beschreibung |
|---|---|---|---|
| NFA-01 | Antwortzeiten | Performance | Baum-Navigation und Editor-Öffnen < 500 ms bei Projekten bis 20 MB; Projekt-Upload/-Laden bis 20 MB < 10 s |
| NFA-02 | Zugriffsschutz | Sicherheit | Session-basierte Auth, CSRF-Schutz, Passwörter gehasht (bcrypt); Groovy-Ausführung ist by design Code-Ausführung auf dem Server → Betrieb unter dediziertem OS-Benutzer mit minimalen Rechten, dokumentiert im Betriebshandbuch |
| NFA-03 | Kompatibilität | Kompatibilität | SoapUI-5.x-Projektformat; Engine = SoapUI OS 5.10.0; Roundtrip-Garantie (Download im Desktop-Client öffenbar); keine proprietären Formaterweiterungen |
| NFA-04 | Deployment | Betrieb | Ein Fat-JAR, JDK 21, Konfiguration über `application.yml`/Umgebungsvariablen (Projektverzeichnis, Mock-Portbereich, Nutzer-Store); Beispiel-systemd-Unit |
| NFA-05 | Robustheit | Zuverlässigkeit | Atomares Speichern der Projektdateien (temp + move); sauberer Shutdown stoppt alle Mocks; kein Datenverlust bei Absturz während des Speicherns |
| NFA-06 | Beobachtbarkeit | Betrieb | Spring Actuator Health-Endpoint; strukturierte Logs (App + SoapUI-Engine zusammengeführt, siehe Logging-Entscheidung in der Spezifikation); Statusübersicht laufender Mocks/Testläufe |
| NFA-07 | Wartbarkeit | Wartbarkeit | SmartBear-Repo + Dependency-Overrides (XmlBeans `sb-fixed`, Ausschlüsse JavaFX/Desktop-UI) zentral im Build dokumentiert; Engine-Version an genau einer Stelle definiert |
| NFA-08 | Skalierungsrahmen | Performance | Single-Instance-Architektur (statischer Engine-Zustand, siehe Ist-Analyse); ausgelegt auf ~10 gleichzeitige Nutzer und ~50 gleichzeitig laufende Mocks; Grenzen dokumentiert |
| NFA-09 | Artefaktgröße | Betrieb | Fat-JAR < 150 MB (JavaFX und Desktop-UI-Bibliotheken ausgeschlossen) |
| NFA-10 | Browser-Support | Kompatibilität | Aktuelle Versionen von Chrome, Firefox, Edge, Safari; keine IE-Unterstützung |
| NFA-11 | Nebenläufigkeit | Zuverlässigkeit | Schreiboperationen pro Projekt serialisiert (Sperre); Testläufe/Mock-Starts verschiedener Projekte laufen parallel ohne gegenseitige Störung |

## 3. Akzeptanzkriterien

Referenzszenario: ein Projekt mit SOAP-Interface (WSDL), einem SOAP-MockService, einem REST-MockService, einer TestSuite mit Request-Step + Assertions, Groovy-Step, Property-Transfer.

- [ ] Upload des Referenzprojekts zeigt den vollständigen Projektbaum (alle Interfaces, TestSuites, TestSteps, MockServices) ohne Fehler.
- [ ] Download ohne zwischenzeitliche Edits liefert eine byte-identische Datei; nach einem Edit über die WebGUI lässt sich die Datei unverändert im SoapUI-Desktop-Client öffnen und zeigt die Änderung.
- [ ] SOAP-Mock starten → externer `curl`-Request an `http://server:port/pfad` liefert die konfigurierte Mock-Response; Stoppen gibt den Port frei (erneutes Binden möglich).
- [ ] REST-Mock: analoges Verhalten.
- [ ] Mock-Request-Log zeigt einen eingehenden Request innerhalb von 2 s in der UI an.
- [ ] Port-Konflikt (Mock-Port belegt) erzeugt eine verständliche Fehlermeldung statt eines Stacktraces.
- [ ] Request-Editor: Request an einen laufenden Mock senden → Response mit Status, Headern, Zeit im UI sichtbar.
- [ ] TestCase-Lauf zeigt Live-Fortschritt; fehlschlagende Assertion wird am Step rot mit Fehlertext angezeigt; Request/Response des Steps sind einsehbar.
- [ ] Laufender TestCase lässt sich abbrechen.
- [ ] Property auf Projektebene ändern → Property-Expansion im nächsten Lauf verwendet den neuen Wert.
- [ ] Groovy-Step: `log.info`-Ausgabe erscheint im Lauf-Log der UI.
- [ ] Unauthentifizierter Zugriff auf UI/API → Redirect zum Login; Mock-Endpunkte antworten ohne Login.
- [ ] Nutzer A sperrt Projekt → Nutzer B sieht read-only und kann nicht speichern; nach Freigabe/Timeout kann B editieren; Admin kann die Sperre brechen.
- [ ] Zwei Nutzer führen gleichzeitig Tests in verschiedenen Projekten aus — beide Läufe liefern korrekte Ergebnisse.
- [ ] App-Restart: als Autostart markierte Mocks laufen danach wieder; nicht markierte sind sauber gestoppt.

## 4. Abhängigkeiten zu anderen Anforderungen

- **Konzept-Entscheidungen** (01-konzept, Abschnitt 7): Thymeleaf/HTMX, Fat-JAR/VM, Dateisystem-Ablage, Portbereich-Modell, Projekt-Sperre, JDK 21 — alle Anforderungen setzen diese voraus.
- **Ist-Analyse-Constraints** (02-ist-analyse): Logging-Vereinheitlichung (Log4j2 vs. Logback) und Dependency-Overrides sind Voraussetzung für NFA-06/NFA-07 und werden in der Spezifikation entschieden.
- FA-14 (Autostart) setzt FA-11 voraus; FA-33/34 setzen FA-30–32 voraus; FA-52 setzt FA-50/51 voraus.
- Die „Kann"-Anforderungen (FA-60 ff.) bauen vollständig auf der Phase-1-Infrastruktur auf und erfordern keine Architekturänderung (gleiche Engine-Registry-Mechanik).

## 5. Priorisierung

**Phase 1 (Muss, in Implementierungsreihenfolge):**

1. Grundgerüst: Spring Boot + eingebettete Engine + Logging-/Dependency-Setup (Basis für alles)
2. Login/Nutzerverwaltung (FA-50/51 — früh, da nachträglicher Einbau alle Endpunkte anfasst)
3. Projektverwaltung: Upload, Download, Liste, Löschen (FA-01–04)
4. Projektbaum (FA-05) + Projekt-Sperre (FA-06)
5. MockServices: Übersicht, Start/Stop, Log, Port-Konflikte, REST, Autostart (FA-10–12, FA-14, FA-15, FA-16)
6. Mock-Editing (FA-13)
7. Request-Editor + Senden + Endpoints (FA-20–23)
8. Tests: Verwaltung, Steps, Assertions, Ausführung, Ergebnisse, Abbruch, Properties (FA-30–36)
9. Groovy-Editoren + Skript-Log (FA-40/41)

**Soll (Phase 1 bei Gelegenheit, sonst Phase-2-Start):** FA-07 (WSDL-Import), FA-24 (ausgehende Auth), FA-52 (Rollen), weitere TestStep-Typen aus FA-31.

**Kann (spätere Phasen):** FA-60–63 — vervollständigen die 1:1-Abbildung (LoadTests, Security, JDBC/JMS, Composite Projects).

**Begründung der Reihenfolge:** Mocks vor Tests, weil der Mock-Betrieb laut Konzept der primäre Servermehrwert ist (dauerhaft laufende Mocks); der Request-Editor davor liefert das früheste durchgängig nutzbare Produkt (Upload → Mock läuft → Request testen).
