package spike;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockResponse;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockRunner;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Smoke-Test: verifiziert, dass die SoapUI-OS-Lib unter JDK 21 headless
 * (1) WSDL importieren, (2) MockService anlegen, (3) MockRunner starten,
 * (4) Requests beantworten und (5) Projekt-Roundtrip (save/load) kann.
 */
public class SmokeTest {

    public static void main(String[] args) throws Exception {
        System.out.println(">>> Java: " + System.getProperty("java.version"));

        // (1) Projekt anlegen + WSDL importieren
        WsdlProject project = new WsdlProject();
        project.setName("SpikeProject");
        String wsdlUrl = new File("src/main/resources/echo.wsdl").toURI().toString();
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project, wsdlUrl);
        System.out.println(">>> WSDL importiert: " + ifaces.length + " Interface(s), "
                + ifaces[0].getOperationCount() + " Operation(en)");

        // (2) MockService mit Default-Response anlegen
        WsdlMockService mock = project.addNewMockService("EchoMock");
        mock.setPort(18089);
        mock.setPath("/echo");
        WsdlMockOperation mockOp = (WsdlMockOperation) mock.addNewMockOperation(ifaces[0].getOperationAt(0));
        WsdlMockResponse resp = mockOp.addNewMockResponse("Default", true);
        resp.setResponseContent(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoResponse xmlns=\"http://example.com/echo\">Hello from Mock"
                + "</EchoResponse></soapenv:Body></soapenv:Envelope>");

        // (3) MockRunner starten
        WsdlMockRunner runner = mock.start();
        System.out.println(">>> MockRunner gestartet, running=" + runner.isRunning());

        // (4) SOAP-Request gegen den laufenden Mock schicken
        String soapRequest =
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><EchoRequest xmlns=\"http://example.com/echo\">Hi"
                + "</EchoRequest></soapenv:Body></soapenv:Envelope>";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:18089/echo"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"http://example.com/echo/Echo\"")
                .POST(HttpRequest.BodyPublishers.ofString(soapRequest))
                .build();
        HttpResponse<String> httpResp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println(">>> Mock-Antwort HTTP " + httpResp.statusCode() + ": " + httpResp.body());

        runner.stop();
        System.out.println(">>> MockRunner gestoppt, running=" + runner.isRunning());

        // (5) Projekt speichern + neu laden (entspricht Download/Upload-Roundtrip)
        File saved = new File("target/spike-project.xml");
        project.saveAs(saved.getAbsolutePath());
        WsdlProject reloaded = new WsdlProject(saved.getAbsolutePath());
        System.out.println(">>> Reload: Interfaces=" + reloaded.getInterfaceCount()
                + ", MockServices=" + reloaded.getMockServiceCount());

        WsdlMockRunner runner2 = reloaded.getMockServiceByName("EchoMock").start();
        HttpResponse<String> httpResp2 = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println(">>> Mock nach Reload HTTP " + httpResp2.statusCode());
        runner2.stop();

        // (6) TestSuite/TestCase mit Groovy-Step ausführen (Testausführungs-API + Skript-Engine)
        com.eviware.soapui.impl.wsdl.WsdlTestSuite suite = project.addNewTestSuite("SpikeSuite");
        com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase tc = suite.addNewTestCase("SpikeCase");
        com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep step = tc.addTestStep(
                com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory.GROOVY_TYPE, "Groovy");
        ((com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep) step)
                .setScript("log.info('Groovy läuft'); return 6*7");
        com.eviware.soapui.impl.wsdl.testcase.WsdlTestCaseRunner tcRunner =
                tc.run(new com.eviware.soapui.support.types.StringToObjectMap(), false);
        System.out.println(">>> TestCase-Lauf: Status=" + tcRunner.getStatus()
                + ", Steps=" + tcRunner.getResults().size());

        SoapUI.shutdown();
        System.out.println(">>> SMOKE TEST OK");
        System.exit(0);
    }
}
