# soapui-webgui

WebGUI für SoapUI-Projekte: SoapUI-Projektdateien hochladen/herunterladen, enthaltene MockServices via MockRunner auf dem Server starten/stoppen und den SoapUI-OS-Funktionsumfang (TestSuites, TestCases, Assertions, Groovy) im Browser nutzen. Engine ist die originale [SoapUI-Open-Source-Bibliothek](https://github.com/SmartBear/soapui) von SmartBear — keine Nachimplementierung.

**Stack:** Java 21 · Spring Boot · Thymeleaf + HTMX · SoapUI OS 5.10.0 (eingebettet, headless)

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [Konzept](docs/01-konzept-soapui-webgui.md) | Problemstellung, Ziele, Lösungsidee, Entscheidungen |
| [Ist-Analyse](docs/02-ist-analyse-soapui-webgui.md) | SoapUI-Lib-Untersuchung, JDK-21-Verifikation, Abhängigkeiten, Risiken |
| [Anforderungsanalyse](docs/03-anforderungsanalyse-soapui-webgui.md) | Funktionale/nicht-funktionale Anforderungen, Akzeptanzkriterien |
| [Spezifikation](docs/04-spezifikation-soapui-webgui.md) | Architektur, Datenmodell, Schnittstellen, Implementierungsplan |

## Spike

`spike/` enthält den ausführbaren Machbarkeitsnachweis (SoapUI 5.10.0 headless unter JDK 21: WSDL-Import, Mock-Start via MockRunner, Testlauf mit Groovy):

```bash
cd spike && JAVA_HOME=<pfad-zu-jdk21> mvn compile exec:exec
```
