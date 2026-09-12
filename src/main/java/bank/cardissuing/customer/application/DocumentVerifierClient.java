package bank.cardissuing.customer.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an identification document so the CMS can compare it with the cardholder's data.
 * Three modes: {@code manual} (default) reads nothing and leaves the comparison to an analyst who
 * sees the image next to the data; {@code http} sends the image to an OCR / identity provider
 * ({@code POST {base-url}} with {documentType, fileName, contentType, base64}, header X-API-Key)
 * that answers {@code {readable, fullName, curp, birthDate, documentNumber, expiresAt, detail}};
 * {@code simulated} (test benches) answers with the customer's own data unless the file name says
 * otherwise: {@code *_mal.*} reads a different person, {@code *_ilegible.*} cannot be read.
 */
@Component
@ConfigurationProperties(prefix = "kyc.document")
@Getter
@Setter
@Slf4j
public class DocumentVerifierClient {

    /** What the provider read. {@code available=false} when the provider failed; {@code readable=false} when the image is unusable. */
    public record Extraction(boolean available, boolean readable, String fullName, String curp, String birthDate,
                             String documentNumber, String expiresAt, String detail) { }

    /** manual | http | simulated */
    private String mode = "manual";
    private String baseUrl = "";
    private String apiKey = "";
    private int timeoutMs = 15000;
    /** Largest file accepted, in bytes. */
    private long maxBytes = 6L * 1024 * 1024;
    private volatile String lastError;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public String mode() { return mode == null ? "manual" : mode.toLowerCase(Locale.ROOT); }
    public boolean manual() { return "manual".equals(mode()); }
    public String lastError() { return lastError; }

    /** Reads the document. In manual mode returns {@code available=false} with the reason "manual" so the caller queues the analyst. */
    public Extraction read(String documentType, String fileName, String contentType, byte[] content, KycSubject subject) {
        switch (mode()) {
            case "simulated": return simulate(fileName, subject);
            case "http": return remote(documentType, fileName, contentType, content);
            default: return new Extraction(false, false, null, null, null, null, null, "cotejo manual: un analista compara la identificación con los datos");
        }
    }

    /** The person the document should belong to (what the CMS captured). */
    public record KycSubject(String fullName, String curp, String birthDate, String documentNumber, String documentExpiresAt) { }

    private Extraction simulate(String fileName, KycSubject s) {
        String f = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (f.contains("_ilegible")) return new Extraction(true, false, null, null, null, null, null, "simulado: imagen ilegible");
        if (f.contains("_mal")) return new Extraction(true, true, "OTRA PERSONA DISTINTA", "XEXX010101HNEXXXA4", "2001-01-01", "IDMEX000000000", s.documentExpiresAt(), "simulado: la identificación es de otra persona");
        return new Extraction(true, true, s.fullName(), s.curp(), s.birthDate(), s.documentNumber(), s.documentExpiresAt(), "simulado: los datos leídos coinciden con lo capturado");
    }

    private Extraction remote(String documentType, String fileName, String contentType, byte[] content) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentType", documentType);
            body.put("fileName", fileName);
            body.put("contentType", contentType);
            body.put("base64", Base64.getEncoder().encodeToString(content));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json").header("Accept", "application/json").header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            JsonNode n = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            lastError = null;
            return new Extraction(true, n.path("readable").asBoolean(true), n.path("fullName").asText(null), n.path("curp").asText(null),
                    n.path("birthDate").asText(null), n.path("documentNumber").asText(null), n.path("expiresAt").asText(null), n.path("detail").asText("leído por el proveedor"));
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Document verification provider unavailable ({}): {}", baseUrl, e.getMessage());
            return new Extraction(false, false, null, null, null, null, null, "proveedor de verificación sin respuesta: " + e.getMessage());
        }
    }

    /** For the third-party registry. */
    public String health() {
        switch (mode()) {
            case "simulated": return "simulado: coincide salvo archivos *_mal.* (otra persona) o *_ilegible.*";
            case "http": return (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) ? "sin URL o clave del proveedor (KYC_DOCUMENT_URL / KYC_DOCUMENT_API_KEY)" : (lastError == null ? "configurado: " + baseUrl : "último error: " + lastError);
            default: return "manual: el analista coteja la identificación desde el expediente";
        }
    }

    public boolean healthy() {
        return !"http".equals(mode()) || (baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank() && lastError == null);
    }
}
