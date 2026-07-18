package de.soapuiweb.engine;

import com.eviware.soapui.impl.rest.RestService;
import com.eviware.soapui.impl.rest.mock.RestMockService;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Interface;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.iface.Request;
import com.eviware.soapui.model.mock.MockOperation;
import com.eviware.soapui.model.mock.MockResponse;
import com.eviware.soapui.model.mock.MockService;
import com.eviware.soapui.model.testsuite.TestCase;
import com.eviware.soapui.model.testsuite.TestStep;
import com.eviware.soapui.model.testsuite.TestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Navigations-Helfer über dem SoapUI-Modell. Adressierung erfolgt über die
 * persistenten ModelItem-UUIDs aus der Projektdatei (Spezifikation 2.1).
 */
public final class ModelItems {

    private ModelItems() {
    }

    /**
     * Kinder eines Knotens. Für das Projekt selbst wird die Reihenfolge
     * explizit festgelegt (Interfaces, TestSuites, MockServices) — wie im
     * Navigator des Desktop-Clients.
     */
    public static List<ModelItem> childrenOf(ModelItem item) {
        if (item instanceof WsdlProject project) {
            List<ModelItem> children = new ArrayList<>();
            children.addAll(project.getInterfaceList());
            children.addAll(project.getTestSuiteList());
            children.addAll(project.getMockServiceList());
            children.addAll(project.getRestMockServiceList());
            return children;
        }
        List<? extends ModelItem> children = item.getChildren();
        return children == null ? List.of() : List.copyOf(children);
    }

    public static Optional<ModelItem> findById(ModelItem root, String id) {
        if (id.equals(root.getId())) {
            return Optional.of(root);
        }
        for (ModelItem child : childrenOf(root)) {
            Optional<ModelItem> found = findById(child, id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    public static String typeLabel(ModelItem item) {
        if (item instanceof WsdlProject) {
            return "Projekt";
        }
        if (item instanceof RestMockService) {
            return "MockService (REST)";
        }
        if (item instanceof MockService) {
            return "MockService (SOAP)";
        }
        if (item instanceof MockOperation) {
            return "MockOperation";
        }
        if (item instanceof MockResponse) {
            return "MockResponse";
        }
        if (item instanceof TestSuite) {
            return "TestSuite";
        }
        if (item instanceof TestCase) {
            return "TestCase";
        }
        if (item instanceof com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep wsdlStep) {
            return "TestStep · " + wsdlStep.getConfig().getType();
        }
        if (item instanceof TestStep) {
            return "TestStep";
        }
        if (item instanceof RestService) {
            return "REST-Service";
        }
        if (item instanceof Interface) {
            return "Interface";
        }
        if (item instanceof Operation) {
            return "Operation";
        }
        if (item instanceof Request) {
            return "Request";
        }
        return item.getClass().getSimpleName();
    }
}
