# Konzept: WebGUI für SoapUI-Projekte

## 1. Zusammenfassung

Es wird eine serverseitig deploybare Webanwendung auf Java-Spring-Boot-Basis entwickelt, die die Funktionalität des SoapUI-Desktop-Clients (Open Source) als moderne Web-Oberfläche bereitstellt. SoapUI-Projektdateien können hochgeladen und heruntergeladen werden; die im Projekt enthaltenen MockServices lassen sich über die von SmartBear bereitgestellte Open-Source-SoapUI-Bibliothek (MockRunner) direkt auf dem Server starten und stoppen. Ziel ist eine funktionale 1:1-Abbildung von SoapUI im Browser, umgesetzt in Phasen (Kernfunktionalität zuerst).

## 2. Problemstellung

SoapUI ist eine reine Desktop-Anwendung. Daraus ergeben sich mehrere Probleme:

- **Mocks laufen nur lokal:** MockServices sind an den Rechner des jeweiligen Entwicklers gebunden. Andere Teams/Systeme können sie nicht zuverlässig als Testumgebung nutzen; nach Feierabend sind sie weg.
- **Kein zentraler Projektzugriff:** SoapUI-Projekte (XML-Dateien) werden per Mail/Repo herumgereicht, es gibt keinen zentralen Ort mit einheitlichem Stand.
- **Installationsaufwand:** Jeder Nutzer benötigt eine lokale SoapUI-Installation samt passender Java-Version.
- **Veraltete UX:** Die SoapUI-Desktop-Oberfläche (Swing) ist funktional, aber nicht zeitgemäß.

## 3. Zielsetzung

- SoapUI-Projekte können über den Browser hochgeladen, verwaltet und wieder heruntergeladen werden — die Datei bleibt dabei vollständig kompatibel zum SoapUI-Desktop-Client (Roundtrip ohne Informationsverlust).
- Die in einem Projekt enthaltenen MockServices (SOAP und REST) können über die Web-UI gestartet und gestoppt werden und sind für externe Consumer über das Netzwerk erreichbar; Laufzeitstatus und Request-Log sind in der UI sichtbar.
- Die Funktionalität von SoapUI Open Source wird 1:1 in der Web-UI abgebildet: Projektbaum (Projekt → Interface → Operation → Request bzw. TestSuite → TestCase → TestStep), Requests editieren und senden, TestSuites/TestCases/TestSteps ausführen, Assertions verwalten und auswerten, Properties auf allen Ebenen, Groovy-Skripte.
- Die Ausführungs-Engine ist die originale Open-Source-SoapUI-Bibliothek von SmartBear — es wird keine eigene Nachimplementierung der Test-/Mock-Semantik gebaut. Dadurch verhält sich die WebGUI garantiert identisch zum Desktop-Client.
- Moderne, reaktionsschnelle Web-UX.

**Messbare Ergebnisse:**

- Ein beliebiges valides SoapUI-5.x-Projekt kann ohne Anpassung hochgeladen, vollständig im Baum dargestellt und ausgeführt werden.
- Ein gestarteter MockService beantwortet Requests externer Clients identisch zum Desktop-MockRunner.
- Ein heruntergeladenes Projekt lässt sich unverändert im SoapUI-Desktop-Client öffnen.

## 4. Lösungsidee

**Backend (Spring Boot, JDK 21):**

- Die Open-Source-SoapUI-Bibliothek (`com.smartbear.soapui:soapui`, GitHub: SmartBear/soapui) wird als Engine eingebettet. Zentrale Klassen: `WsdlProject` (Laden/Speichern von Projektdateien), `WsdlMockService`/`RestMockService` mit `MockRunner` (Mock-Lifecycle), `WsdlTestCaseRunner` (Testausführung). Die Kompatibilität der Lib mit JDK 21 wird in der Ist-Analyse verifiziert; nötigenfalls werden `--add-opens`-Optionen oder eine angepasste Dependency-Auflösung dokumentiert.
- Eine REST-/HTTP-API kapselt Projektverwaltung (Upload/Download/Liste), Projektstruktur (Baum), Editieroperationen, Test- und Mock-Ausführung.
- Laufzeit-Events (Testfortschritt, Log-Einträge, Mock-Request-Log) werden per Server-Sent-Events an die UI gestreamt.
- Die SoapUI-Mock-Engine startet intern eigene Jetty-Listener. MockServices binden **1:1 die im Projekt konfigurierten Ports** (innerhalb eines per Firewall freigegebenen Portbereichs); die Spring-Boot-Anwendung selbst läuft auf Tomcat, um Klassenpfad-Konflikte mit dem von SoapUI mitgebrachten Jetty zu vermeiden.
- **Projektablage im Dateisystem:** Projekte werden serverseitig als originale SoapUI-XML-Dateien in einem konfigurierbaren Verzeichnis gehalten (Single Source of Truth), sodass Download = Originaldatei gilt.
- **Authentifizierung:** Einfacher Form-Login mit lokal verwalteten Nutzern über Spring Security (relevant, da Groovy-Skripte in Projekten Code auf dem Server ausführen).
- **Nebenläufigkeit:** Projekt-Sperre — wer ein Projekt bearbeitet, sperrt es; andere Nutzer sehen es read-only, bis die Sperre freigegeben wird oder abläuft.

**Frontend (serverseitig gerendert):**

- Thymeleaf + HTMX aus derselben Spring-Boot-Anwendung — ein einziger Stack, kein separates SPA-Build. Moderne UX über HTMX-Partial-Updates (inkl. SSE-Extension für Live-Logs), leichtgewichtige Interaktivität via Alpine.js, Code-Editor-Komponente (z. B. CodeMirror als statisches Asset) für XML/JSON/Groovy mit Syntaxhighlighting.
- Layout angelehnt an die bekannte SoapUI-Arbeitsweise: Projektbaum links, Editor-/Detailbereich rechts (Request-Editor, Response-Ansicht, Assertion-Panel), Log-Bereich unten.

**Deployment:**

- Ausführbares Spring-Boot-Fat-JAR, direkt mit JDK 21 auf der Ziel-VM gestartet. UI-Assets sind im JAR enthalten.

## 5. Betroffene Komponenten

Greenfield-Projekt — es existiert noch keine Codebasis. Neues Repository `soapui-webgui`:

| Komponente | Beschreibung |
|---|---|
| Spring-Boot-Anwendung | Bettet SoapUI-Lib ein; Controller (Thymeleaf/HTMX-Views + HTTP-API), Services für Projekt-, Mock- und Testverwaltung |
| Thymeleaf-Templates + statische Assets | Serverseitige UI (HTMX, Alpine.js, CodeMirror, CSS) |
| `docs/` | Konzept-, Analyse- und Spezifikationsdokumente |

**Externe Abhängigkeiten:**

- SoapUI Open Source Library von SmartBear (EUPL-lizenziert, eigenes Maven-Repository von SmartBear) inkl. transitiver Abhängigkeiten (Jetty, Groovy, XmlBeans, wsdl4j, Apache HttpClient u. a.) — Details werden in der Ist-Analyse erhoben.

## 6. Abgrenzung

Explizit NICHT Teil dieser Anforderung:

- **ReadyAPI-/Pro-Funktionen** (DataSources/DataSinks, Pro-Reporting, Repository-Integration etc.) — es wird ausschließlich der Funktionsumfang von SoapUI **Open Source** abgebildet.
- **SoapUI-Desktop-Plugins:** Drittanbieter-Plugins des Desktop-Clients werden nicht unterstützt.
- **Nachbau der Engine:** Test-/Mock-/Assertion-Logik wird nicht reimplementiert, sondern ausschließlich über die SmartBear-Bibliothek genutzt.
- **Migration/Konvertierung** fremder Formate (Postman, ReadyAPI-Composite-Spezialitäten) ist nicht Ziel.
- **Nicht in Phase 1** (folgt in späteren Phasen der 1:1-Abbildung): LoadTests, Security-Tests, JDBC-/JMS-/AMF-TestSteps, kollaborative Echtzeit-Bearbeitung.

## 7. Entscheidungen

| # | Frage | Entscheidung |
|---|---|---|
| 1 | Priorisierung der 1:1-Abbildung | Phasenweise: Phase 1 = Projekt-Upload/-Download, MockServices (SOAP+REST), Request-Editor, TestSuites/TestCases/Assertions, Properties, Groovy-Skripte; danach LoadTests, Security-Tests, JDBC/JMS/AMF |
| 2 | Nutzer & Authentifizierung | Einfacher Form-Login mit lokal verwalteten Nutzern (Spring Security) |
| 3 | Frontend-Stack | Serverseitig: Thymeleaf + HTMX (+ Alpine.js, CodeMirror) |
| 4 | Deployment | Fat-JAR auf VM |
| 5 | Projektablage | Dateisystem (originale SoapUI-XML-Dateien in konfigurierbarem Verzeichnis) |
| 6 | Mock-Erreichbarkeit | Portbereich, 1:1 wie im Projekt konfiguriert (Firewall gibt Bereich frei) |
| 7 | Gleichzeitige Bearbeitung | Projekt-Sperre (Bearbeiter sperrt, andere read-only, Sperre mit Timeout) |
| 8 | JDK-Version Zielserver | JDK 21 LTS (Kompatibilität der SoapUI-Lib wird in der Ist-Analyse verifiziert) |

## 8. Offene Fragen

Keine — alle Fragen wurden am 2026-07-17 entschieden (siehe Abschnitt 7).
