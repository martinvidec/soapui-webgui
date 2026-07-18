package de.soapuiweb.engine;

import com.eviware.soapui.impl.support.AbstractHttpRequest;
import com.eviware.soapui.impl.wsdl.WsdlSubmitContext;
import com.eviware.soapui.impl.wsdl.submit.transports.http.HttpResponse;
import com.eviware.soapui.model.iface.Request;
import com.eviware.soapui.model.iface.Response;
import com.eviware.soapui.model.iface.Submit;
import com.eviware.soapui.support.xml.XmlUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Führt Requests über die SoapUI-Engine aus (FA-21): asynchroner Submit mit
 * Timeout und Abbruch, Ergebnis aufbereitet für das Response-Panel.
 */
@Component
public class RequestSubmitter {

    public record Header(String name, String value) {
    }

    public record SubmitResult(boolean success, int status, long timeTakenMs, String sizeLabel,
                               List<Header> headers, String bodyPretty, String bodyRaw,
                               String error) {

        static SubmitResult failure(String error) {
            return new SubmitResult(false, 0, 0, "", List.of(), "", "", error);
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    public SubmitResult submit(AbstractHttpRequest<?> request) {
        Submit submit;
        try {
            submit = request.submit(new WsdlSubmitContext(request), true);
        } catch (Request.SubmitException e) {
            return SubmitResult.failure("Submit fehlgeschlagen: " + e.getMessage());
        }
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while ((submit.getStatus() == Submit.Status.RUNNING
                || submit.getStatus() == Submit.Status.INITIALIZED)
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                submit.cancel();
                return SubmitResult.failure("Unterbrochen");
            }
        }
        if (submit.getStatus() == Submit.Status.RUNNING) {
            submit.cancel();
            return SubmitResult.failure("Timeout nach " + (DEFAULT_TIMEOUT_MS / 1000) + " s");
        }
        if (submit.getError() != null) {
            return SubmitResult.failure(rootMessage(submit.getError()));
        }
        Response response = submit.getResponse();
        if (response == null) {
            return SubmitResult.failure("Keine Antwort erhalten");
        }
        String raw = response.getContentAsString();
        raw = raw == null ? "" : raw;
        int status = response instanceof HttpResponse http ? http.getStatusCode() : 0;
        List<Header> headers = new ArrayList<>();
        if (response.getResponseHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : response.getResponseHeaders().entrySet()) {
                entry.getValue().forEach(v -> headers.add(new Header(entry.getKey(), v)));
            }
        }
        return new SubmitResult(true, status, response.getTimeTaken(),
                formatSize(raw.getBytes().length), headers, prettyOrRaw(raw), raw, null);
    }

    private static String prettyOrRaw(String content) {
        if (content == null || content.isBlank() || !content.stripLeading().startsWith("<")) {
            return content;
        }
        try {
            return XmlUtils.prettyPrintXml(content);
        } catch (Exception e) {
            return content;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format("%.1f KB", bytes / 1024.0);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
