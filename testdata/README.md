# Testdaten

## beispiel-projekt.xml

Demo-SoapUI-Projekt für die WebGUI — mit der echten Engine generiert und validiert
(Generator: `spike/src/main/java/spike/ExampleProjectGenerator.java`).

**Inhalt:**

| Bereich | Details |
|---|---|
| Interface | `EchoBinding` (aus `spike/src/main/resources/echo.wsdl`, Definition eingebettet) mit Request „Standard-Anfrage" — nutzt Property-Expansion `${#Project#begruessung}` |
| SOAP-Mock | `EchoMock` auf Port **18089**, Pfad `/echo`, eine Response + Start-Skript |
| REST-Mock | `PingMock` auf Port **18090**, `GET /api/ping` → `{"status": "pong"}` |
| TestSuite | „Smoke-Tests" mit zwei TestCases (s. u.) |
| Properties | Projekt-Properties `umgebung` und `begruessung` |

**TestCase „Echo-Test":** Request-Step gegen den EchoMock mit drei Assertions
(XPath Match, Valid HTTP Status Codes, Response SLA), Groovy-Step mit `log.info`
und Property-Expansion, Delay-Step; Setup-Skript am Case.

**TestCase „Property-Transfer-Demo":** zwei Properties-Steps (Quelle/Ziel),
Property-Transfer dazwischen, Groovy-Step prüft den übertragenen Wert per `assert`.

## Verwendung

1. In der WebGUI anmelden → Projektliste → `beispiel-projekt.xml` hochladen
2. Unter **Mocks** den `EchoMock` starten, dann von außen testen:
   ```bash
   curl -s -X POST -H 'Content-Type: text/xml' \
     -d '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body><EchoRequest xmlns="http://example.com/echo">Hi</EchoRequest></soapenv:Body></soapenv:Envelope>' \
     http://localhost:18089/echo
   curl -s http://localhost:18090/api/ping
   ```
3. Im Projektbaum den TestCase **Echo-Test** öffnen → „▶ TestCase ausführen"
   (EchoMock muss laufen) — Live-Log inkl. Groovy-Ausgaben und Assertion-Ergebnisse
4. Roundtrip-Check: Projekt herunterladen und im SoapUI-Desktop-Client öffnen

**Neu generieren:**

```bash
cd spike
JAVA_HOME=<jdk21> mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Djava.awt.headless=true -cp "target/classes:$(cat target/cp.txt)" spike.ExampleProjectGenerator
```
