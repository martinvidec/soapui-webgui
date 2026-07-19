package spike;

import com.eviware.soapui.impl.rest.RestRequestInterface;
import com.eviware.soapui.impl.rest.mock.RestMockAction;
import com.eviware.soapui.impl.rest.mock.RestMockService;
import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockService;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlImporter;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfer;
import com.eviware.soapui.impl.wsdl.teststeps.PropertyTransfersTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlDelayTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlGroovyScriptTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlPropertiesTestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestRequestStep;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.ResponseSLAAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.basic.XPathContainsAssertion;
import com.eviware.soapui.impl.wsdl.teststeps.registry.DelayStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.GroovyScriptStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.PropertiesStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.PropertyTransfersStepFactory;
import com.eviware.soapui.impl.wsdl.teststeps.registry.WsdlTestRequestStepFactory;
import com.eviware.soapui.security.assertion.ValidHttpStatusCodesAssertion;

import java.io.File;

/**
 * Erzeugt das Beispielprojekt unter testdata/ mit der echten Engine —
 * garantiert valide und Desktop-kompatibel. Aufruf siehe testdata/README.md.
 */
public class ExampleProjectGenerator {

    private static final String NS = "http://example.com/echo";

    public static void main(String[] args) throws Exception {
        File target = new File(args.length > 0 ? args[0] : "../testdata/beispiel-projekt.xml");
        target.getParentFile().mkdirs();

        WsdlProject project = new WsdlProject();
        project.setName("Beispielprojekt");
        project.setDescription("Demo-Projekt für die SoapUI WebGUI: SOAP- und REST-Mock, "
                + "TestSuite mit Assertions, Groovy, Property-Transfer und Skripten.");
        project.setPropertyValue("umgebung", "lokal");
        project.setPropertyValue("begruessung", "Hallo aus dem Beispielprojekt");

        // Interface + Request
        WsdlInterface[] ifaces = WsdlImporter.importWsdl(project,
                new File("src/main/resources/echo.wsdl").toURI().toString());
        WsdlOperation echo = ifaces[0].getOperationAt(0);
        WsdlRequest request = echo.addNewRequest("Standard-Anfrage");
        request.setRequestContent(envelope("EchoRequest", "${#Project#begruessung}"));
        request.setEndpoint("http://localhost:18089/echo");

        // SOAP-Mock
        WsdlMockService soapMock = project.addNewMockService("EchoMock");
        soapMock.setPort(18089);
        soapMock.setPath("/echo");
        soapMock.setStartScript("log.info 'EchoMock gestartet'");
        WsdlMockOperation mockOp = (WsdlMockOperation) soapMock.addNewMockOperation(echo);
        mockOp.addNewMockResponse("Standard-Antwort", true)
                .setResponseContent(envelope("EchoResponse", "Hallo aus dem EchoMock"));

        // REST-Mock
        RestMockService restMock = project.addNewRestMockService("PingMock");
        restMock.setPort(18090);
        restMock.setPath("/api");
        RestMockAction ping = restMock.addEmptyMockAction(
                RestRequestInterface.HttpMethod.GET, "/ping");
        ping.addNewMockResponse("Pong").setResponseContent("{\"status\": \"pong\"}");

        // TestSuite
        WsdlTestSuite suite = project.addNewTestSuite("Smoke-Tests");

        WsdlTestCase echoTest = suite.addNewTestCase("Echo-Test");
        echoTest.setSetupScript("log.info 'Setup: Echo-Test startet'");
        WsdlTestRequestStep requestStep = (WsdlTestRequestStep) echoTest.addTestStep(
                WsdlTestRequestStepFactory.createConfig(request, "Echo-Anfrage"));
        XPathContainsAssertion xpath = (XPathContainsAssertion)
                requestStep.addAssertion(XPathContainsAssertion.LABEL);
        xpath.setPath("//*[local-name()='EchoResponse']/text()");
        xpath.setExpectedContent("Hallo aus dem EchoMock");
        ((ValidHttpStatusCodesAssertion) requestStep
                .addAssertion(ValidHttpStatusCodesAssertion.LABEL)).setCodes("200");
        ((ResponseSLAAssertion) requestStep
                .addAssertion(ResponseSLAAssertion.LABEL)).setSLA("2000");
        ((WsdlGroovyScriptTestStep) echoTest.addTestStep(
                GroovyScriptStepFactory.GROOVY_TYPE, "Ergebnis loggen"))
                .setScript("log.info 'Echo-Test in Umgebung: ' "
                        + "+ context.expand('${#Project#umgebung}')");
        ((WsdlDelayTestStep) echoTest.addTestStep(
                DelayStepFactory.DELAY_TYPE, "Kurze Pause")).setDelay(200);

        WsdlTestCase transferDemo = suite.addNewTestCase("Property-Transfer-Demo");
        WsdlPropertiesTestStep quelle = (WsdlPropertiesTestStep) transferDemo.addTestStep(
                PropertiesStepFactory.PROPERTIES_TYPE, "Quelle");
        quelle.addProperty("eingabe");
        quelle.setPropertyValue("eingabe", "Wert-aus-der-Quelle");
        WsdlPropertiesTestStep ziel = (WsdlPropertiesTestStep) transferDemo.addTestStep(
                PropertiesStepFactory.PROPERTIES_TYPE, "Ziel");
        ziel.addProperty("ausgabe");
        PropertyTransfersTestStep transfers = (PropertyTransfersTestStep) transferDemo
                .addTestStep(PropertyTransfersStepFactory.TRANSFER_TYPE, "Übertragen");
        PropertyTransfer transfer = transfers.addTransfer("Quelle nach Ziel");
        transfer.setSourceStepName("Quelle");
        transfer.setSourcePropertyName("eingabe");
        transfer.setTargetStepName("Ziel");
        transfer.setTargetPropertyName("ausgabe");
        ((WsdlGroovyScriptTestStep) transferDemo.addTestStep(
                GroovyScriptStepFactory.GROOVY_TYPE, "Ergebnis pruefen"))
                .setScript("def wert = context.expand('${Ziel#ausgabe}')\n"
                        + "log.info 'Übertragener Wert: ' + wert\n"
                        + "assert wert == 'Wert-aus-der-Quelle'");

        project.saveAs(target.getAbsolutePath());
        project.release();

        // Validierung: Datei muss vollständig wieder ladbar sein
        WsdlProject reloaded = new WsdlProject(target.getAbsolutePath());
        System.out.println(">>> Gespeichert: " + target.getAbsolutePath());
        System.out.println(">>> Validierung: Interfaces=" + reloaded.getInterfaceCount()
                + ", TestSuites=" + reloaded.getTestSuiteCount()
                + ", MockServices=" + reloaded.getMockServiceCount()
                + ", REST-Mocks=" + reloaded.getRestMockServiceCount());
        reloaded.release();
        System.out.println(">>> GENERATOR OK");
        System.exit(0);
    }

    private static String envelope(String element, String text) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "   <soapenv:Body>\n"
                + "      <" + element + " xmlns=\"" + NS + "\">" + text + "</" + element + ">\n"
                + "   </soapenv:Body>\n"
                + "</soapenv:Envelope>";
    }
}
