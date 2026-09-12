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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screening of a prospective cardholder against the restricted lists (OFAC, ONU, PLD nacional,
 * personas políticamente expuestas). Two modes: {@code simulated} answers from a short local list
 * of test tokens so the flow can be demonstrated, {@code http} asks the list provider
 * ({@code POST {base-url}} with name, CURP, RFC and birth date, header X-API-Key) and expects
 * {@code {hit, lists[], score, detail}}. If the provider fails the customer is not verified
 * automatically: the case goes to manual review, never silently through.
 */
@Component
@ConfigurationProperties(prefix = "kyc.screening")
@Getter
@Setter
@Slf4j
public class KycScreeningClient {

    public record Result(boolean available, boolean hit, List<String> lists, int score, String detail) { }

    /** simulated | http */
    private String mode = "simulated";
    private String baseUrl = "";
    private String apiKey = "";
    private int timeoutMs = 4000;
    /** Tokens that produce a hit in simulated mode (name contains one, or the CURP starts with one). */
    private String simulatedHits = "LISTA NEGRA;SANCIONADO;PRUEBA PLD";
    private volatile String lastError;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public boolean simulated() { return !"http".equalsIgnoreCase(mode); }

    public String lastError() { return lastError; }

    public Result screen(String fullName, String curp, String rfc, LocalDate birthDate) {
        if (simulated()) return simulate(fullName, curp);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fullName", fullName);
            body.put("curp", curp);
            body.put("rfc", rfc);
            body.put("birthDate", birthDate != null ? birthDate.toString() : null);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json").header("Accept", "application/json").header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            JsonNode n = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            List<String> lists = new ArrayList<>();
            n.path("lists").forEach(x -> lists.add(x.asText()));
            lastError = null;
            return new Result(true, n.path("hit").asBoolean(false), lists, n.path("score").asInt(0), n.path("detail").asText("proveedor de listas"));
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("KYC screening provider unavailable ({}): {}", baseUrl, e.getMessage());
            return new Result(false, false, List.of(), 0, "proveedor de listas sin respuesta: " + e.getMessage());
        }
    }

    private Result simulate(String fullName, String curp) {
        String name = CurpRfc.normalize(fullName);
        String c = curp == null ? "" : curp.trim().toUpperCase();
        for (String token : Arrays.stream(simulatedHits.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            String t = CurpRfc.normalize(token);
            if (name.contains(t) || (!c.isEmpty() && c.startsWith(t.replace(" ", "")))) {
                return new Result(true, true, List.of("OFAC-SDN (simulada)", "PLD nacional (simulada)"), 95, "coincidencia con '" + token + "' en la lista simulada");
            }
        }
        return new Result(true, false, List.of(), 0, "sin coincidencias en las listas simuladas (OFAC, ONU, PLD, PEP)");
    }

    /** For the third-party registry: whether the provider answers right now. */
    public Result health() {
        if (simulated()) return new Result(true, false, List.of(), 0, "simulado: coincide con " + simulatedHits.replace(";", ", "));
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) return new Result(false, false, List.of(), 0, "sin URL o clave del proveedor de listas (KYC_SCREENING_URL / KYC_SCREENING_API_KEY)");
        Result r = screen("HEALTHCHECK CMS", null, null, null);
        return new Result(r.available(), false, List.of(), 0, r.available() ? "responde" : r.detail());
    }
}
