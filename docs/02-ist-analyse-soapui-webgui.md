# Ist-Analyse: WebGUI für SoapUI-Projekte

## 1. Aktueller Zustand

Greenfield-Projekt — es existiert noch keine eigene Codebasis. Die Ist-Analyse konzentriert sich daher auf die **einzubettende SoapUI-Open-Source-Bibliothek** und deren praktisch verifizierte Eignung für das Vorhaben (Konzept siehe [01-konzept-soapui-webgui.md](01-konzept-soapui-webgui.md)).

**Zustand des SoapUI-OS-Ökosystems (Stand 2026-07-17):**

| Aspekt | Befund |
|---|---|
| Aktuelle Version | **5.10.0** (Juni 2026) |
| Wartungszustand | Aktiv: 5.9.x/5.10.0 in 2025/2026, CVE-Fixes (slf4j, log4j, htmlunit, dom4j, xstream in 5.10.0) |
| Lizenz | **EUPL 1.1** (verifiziert via LICENSE.md im Repo) |
| Offizielle Java-Version | OpenJDK 17 (seit 5.8.0; davor Java 16 ab 5.7.0) |
| **JDK 21** | **Praktisch verifiziert:** Lib läuft headless unter OpenJDK 21.0.11 ohne `--add-opens`-Workarounds (siehe Abschnitt 6) |
| Bezugsquelle | Nur SmartBear-Nexus: `https://rapi.tools.ops.smartbear.io/nexus/content/groups/public/` (Redirect von `smartbearsoftware.com/repository/maven2/`). **Nicht auf Maven Central.** |
| Maven-Koordinaten | `com.smartbear.soapui:soapui:5.10.0` |
| Repo-Struktur | Module: `soapui` (Kern, ~12,5 MB JAR), `soapui-system-test`, `soapui-installer`, `soapui-maven-plugin`, `soapui-maven-plugin-tester` |

## 2. Relevante Dateien und Komponenten

Für die Einbettung relevante API der SoapUI-Lib (alle im Spike praktisch verwendet und funktionsfähig):

| Klasse/Komponente | Beschreibung | Relevanz |
|---|---|---|
| `com.eviware.soapui.impl.wsdl.WsdlProject` | Projekt laden (Konstruktor mit Dateipfad), erzeugen, `saveAs()` | Kern von Upload/Download und aller Projektoperationen |
| `com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter` | WSDL-Import, erzeugt `WsdlInterface`s mit Operationen | Anlegen neuer SOAP-Interfaces |
| `com.eviware.soapui.impl.wsdl.mock.WsdlMockService` | MockService: `setPort()`, `setPath()`, `addNewMockOperation()`, `start()` | Mock-Verwaltung |
| `com.eviware.soapui.impl.wsdl.mock.WsdlMockRunner` | Laufender Mock: `isRunning()`, `stop()`, Zugriff auf MockResults | Mock-Lifecycle + Request-Log |
| `com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation` / `WsdlMockResponse` | Mock-Operationen und Antworten (`setResponseContent()`) | Mock-Editing |
| `com.eviware.soapui.impl.rest.mock.RestMockService` | REST-Pendant zu WsdlMockService | REST-Mocks (in Phase 1, im Spike nicht gesondert getestet) |
| `com.eviware.soapui.impl.wsdl.WsdlTestSuite` / `testcase.WsdlTestCase` | TestSuite/TestCase-Struktur, `addNewTestCase()`, `addTestStep()` | Testverwaltung |
| `com.eviware.soapui.impl.wsdl.testcase.WsdlTestCaseRunner` | `tc.run(properties, async)` → Status, Step-Results | Testausführung |
| `com.eviware.soapui.impl.wsdl.teststeps.registry.*StepFactory` | Registry der TestStep-Typen (Groovy, Request, PropertyTransfer, …) | Anlegen von TestSteps |
| `com.eviware.soapui.SoapUI` (statisch) | Globale Settings, `shutdown()`, MockEngine | Initialisierung/Lifecycle der Engine, globaler Zustand |

Eigene Artefakte aus der Analyse (im Repo):

| Datei | Beschreibung |
|---|---|
| `spike/pom.xml` | Minimal-POM: SmartBear-Repo + `soapui:5.10.0`, Java 21 |
| `spike/src/main/java/spike/SmokeTest.java` | Ausführbarer Smoke-Test (Abschnitt 6) |
| `spike/src/main/resources/echo.wsdl` | Minimales Echo-WSDL (doc/literal) als Testfixture |
| `spike/dependency-tree.txt` | Vollständiger Dependency-Tree (251 Zeilen) |

## 3. Bestehende Abhängigkeiten

Verifiziert per `mvn dependency:tree` / `dependency:copy-dependencies` (JDK 21, Maven 3.8.3):

- **Gesamt: 250 JARs, 274 MB** Laufzeit-Classpath.
- **JavaFX 17.0.12: 171 MB davon** — wird für *alle* Plattformen (win/linux/mac/mac-aarch64) gezogen, dient nur der Desktop-UI (HTML-Rendering). Headless verzichtbar → Ausschluss reduziert den Classpath auf ~103 MB.
- **Jetty 6.1.26** (`jetty:jetty`, Pakete `org.mortbay.*`) — die interne Mock-Engine. Uralt, aber funktionsfähig (Spike-verifiziert). Wichtig: kollidiert *nicht* mit modernem Jetty (`org.eclipse.jetty`), da andere Koordinaten und Pakete.
- **Groovy 3.0.19** (`groovy-all` als POM-Dependency, zieht alle Module inkl. console/ant) — Skript-Engine.
- **XmlBeans `3.1.1-sb-fixed`** — von SmartBear gepatchte Version, existiert **nur im SmartBear-Nexus**. Konsequenz: Build funktioniert nicht gegen Maven Central allein; das SmartBear-Repo ist ein Muss (oder eigener Proxy/Mirror).
- **Apache HttpClient 4.5.5** — Request-Ausführung. Kein Konflikt mit Spring Boot 3 (das nutzt HttpClient 5 unter anderen Koordinaten).
- **Log4j2 2.26.0 + `log4j-slf4j2-impl`** — SoapUI loggt über Log4j2 und bindet SLF4J→Log4j2. Konfliktpotenzial mit Spring Boots Standard-Logback (zwei SLF4J-Bindings) → eine Seite muss ausgeschlossen/umgeleitet werden.
- Weitere Schwergewichte: Saxon 9.1 (XSLT/XPath), WSS4J/XMLSec (WS-Security), wsdl4j, htmlunit, Rhino, JGoodies/RSyntaxTextArea (Desktop-UI-Reste).

## 4. Bekannte Einschränkungen

- **Desktop-Ballast im Artefakt:** Das `soapui`-JAR enthält die komplette Swing-UI; UI-Bibliotheken (JavaFX, JGoodies, RSyntaxTextArea) hängen als Compile-Dependencies dran. Headless-Betrieb funktioniert (verifiziert, `java.awt.headless=true`), aber die UI-Deps müssen aktiv ausgeschlossen werden, sonst wird das Fat-JAR ~280 MB groß.
- **Globaler statischer Zustand:** `SoapUI.getSettings()`, MockEngine und Log-Konfiguration sind JVM-weite Singletons. Pro JVM gibt es *eine* Settings-Welt — nutzerspezifische SoapUI-Preferences (z. B. Proxy-Einstellungen) sind nicht pro Session isolierbar.
- **Kein Thread-Safety-Vertrag:** Die Lib ist für eine Single-User-Desktop-App gebaut. Gleichzeitige Läufe/Änderungen am *selben* Projektobjekt sind nicht abgesichert — Nebenläufigkeitskontrolle (Projekt-Sperre laut Konzept, Serialisierung von Läufen pro Projekt) muss die einbettende Anwendung leisten.
- **Jetty-6-Mock-Engine:** HTTP/1.1; SSL für Mocks nur über globale SoapUI-Settings. Ports werden pro MockService gebunden (1:1-Verhalten, wie im Konzept entschieden).
- **`ext/`-Verzeichnis:** Die Lib warnt beim Start über ein fehlendes `ext/`-Verzeichnis (externe Libs, z. B. JDBC-Treiber). Harmlos; für JDBC-TestSteps (spätere Phase) muss es bereitgestellt werden.
- **Repo-Verfügbarkeit:** Alle Builds hängen am SmartBear-Nexus (historisch mehrfach umgezogen — der alte `smartbearsoftware.com`-Pfad ist heute ein Redirect). Für reproduzierbare Builds ist ein eigener Mirror/Proxy (oder Vendoring) ratsam.
- **EUPL 1.1:** Copyleft-Lizenz. Für internen Betrieb (keine Weitergabe der Software) unkritisch; bei Distribution der WebGUI an Dritte müssten die EUPL-Pflichten (Quellcode-Verfügbarkeit für die Lib, Kompatibilitätsklauseln) beachtet werden.

## 5. Risiken bei Änderung

Risiken bei der Einbettung in eine Spring-Boot-3-Anwendung:

- **Logging-Kollision (hoch, sicher eintretend):** Spring Boot bringt Logback als SLF4J-Binding, SoapUI bringt `log4j-slf4j2-impl`. Zwei Bindings auf dem Classpath → Konflikt. Lösung in der Spezifikation festzulegen (z. B. Logback ausschließen und einheitlich Log4j2 nutzen, oder umgekehrt).
- **Version-Konvergenz (mittel):** Überschneidungen bei commons-*, Guava, Saxon, XmlBeans zwischen SoapUI und ggf. später ergänzten Libs. Die gepatchte XmlBeans-Version `3.1.1-sb-fixed` muss sich im Dependency-Management durchsetzen, sonst drohen subtile Laufzeitfehler.
- **Servlet-Container (niedrig, durch Konzept entschärft):** Spring Boot läuft auf Tomcat (Starter-Default), SoapUI-Mocks auf ihrem eigenen Jetty 6 — keine Koordinaten-/Paketkollision (verifiziert im Dependency-Tree). `spring-boot-starter-jetty` darf nicht verwendet werden, um Verwirrung zu vermeiden.
- **Engine-Upgrade-Pfad (mittel):** SoapUI-Releases erscheinen unregelmäßig (5.8.0→5.9.0: ~1 Jahr). Sicherheitsfixes in transitiven Deps kommen ggf. verzögert; ein Dependency-Override-Mechanismus im eigenen Build ist einzuplanen.
- **Nebenläufigkeit (hoch bei Missachtung):** Mehrere gleichzeitige Web-Nutzer treffen auf eine Single-User-Engine. Ohne Serialisierung von Schreiboperationen pro Projekt (Sperre) drohen korrupte Projektzustände.

## 6. Praktische Verifikation (Spike)

Der Spike (`spike/`) wurde mit **OpenJDK 21.0.11** und SoapUI **5.10.0** ausgeführt und deckt die Kern-Use-Cases der WebGUI ab. Vollständiger Ablauf mit Ergebnis:

| # | Schritt | Ergebnis |
|---|---|---|
| 1 | `WsdlProject` erzeugen, WSDL importieren (`WsdlImporter`) | ✅ 1 Interface, 1 Operation |
| 2 | `WsdlMockService` anlegen (Port 18089, Pfad `/echo`), MockOperation + Default-Response | ✅ |
| 3 | `mock.start()` → `WsdlMockRunner` (startet Jetty-6-Listener) | ✅ `running=true` |
| 4 | Externer SOAP-Request per `java.net.http.HttpClient` gegen den Mock | ✅ HTTP 200 mit konfigurierter Mock-Response |
| 5 | `runner.stop()` | ✅ `running=false`, Port freigegeben |
| 6 | Projekt `saveAs()` → mit `new WsdlProject(pfad)` neu laden → Mock erneut starten | ✅ Struktur vollständig, Mock antwortet wieder HTTP 200 (**Upload/Download-Roundtrip nachgewiesen**) |
| 7 | TestSuite/TestCase mit Groovy-TestStep anlegen, `WsdlTestCaseRunner` ausführen | ✅ `Status=FINISHED`, Groovy-Engine funktioniert |

**Beobachtungen:** Nur harmlose Warnungen (fehlendes `ext/`-Verzeichnis, XSD-Kompatibilitätswarnungen der mitgelieferten SOAP-Encoding-Schemata). Keine Reflection-/Module-System-Fehler unter JDK 21, kein `--add-opens` nötig. Headless-Betrieb (`-Djava.awt.headless=true`) problemlos.

**Reproduktion:** `cd spike && JAVA_HOME=<jdk21> mvn compile exec:exec`

**Nicht im Spike verifiziert** (für spätere Verifikation in der Implementierung vorgemerkt): REST-Mocks (`RestMockService`), WsdlTestRequest-Steps mit Assertions gegen einen laufenden Mock, Verhalten bei parallelen Testläufen, SSL-Mocks.
