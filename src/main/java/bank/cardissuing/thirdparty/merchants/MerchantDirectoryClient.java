package bank.cardissuing.thirdparty.merchants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the affiliated merchants from the acquiring portal ({@code GET /merchants}) with the
 * integration credential. The listing is cached for a minute; when the portal does not answer
 * the last good listing is served and the caller is told the source is stale, so the console
 * still lets the operator type a merchant by hand.
 */
@Component
@Slf4j
public class MerchantDirectoryClient {

    public record Merchant(Long id, String code, String name, String rfc, String status, boolean blocked, String kind, String subMid) { }

    public record Listing(boolean available, boolean fromCache, String detail, Instant fetchedAt, List<Merchant> merchants) { }

    private final MerchantDirectorySettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile List<Merchant> cached = List.of();
    private volatile Instant cachedAt;
    private volatile String lastError;

    public MerchantDirectoryClient(MerchantDirectorySettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(500, settings.getTimeoutMs()))).build();
    }

    public boolean configured() {
        return settings.isEnabled() && settings.getApiKey() != null && !settings.getApiKey().isBlank();
    }

    /** Every merchant the portal knows, cached; {@code search} filters by name, code or RFC. */
    public Listing list(String search) {
        Listing all = all();
        if (search == null || search.isBlank()) return all;
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<Merchant> out = all.merchants().stream().filter(m ->
                (m.name() != null && m.name().toLowerCase(Locale.ROOT).contains(q))
                        || (m.code() != null && m.code().toLowerCase(Locale.ROOT).contains(q))
                        || (m.rfc() != null && m.rfc().toLowerCase(Locale.ROOT).contains(q))).toList();
        return new Listing(all.available(), all.fromCache(), all.detail(), all.fetchedAt(), out);
    }

    public Listing all() {
        if (!configured()) return new Listing(false, false, "portal de negocios sin configurar (MERCHANT_PORTAL_API_KEY)", null, List.of());
        if (cachedAt != null && cachedAt.plusSeconds(settings.getCacheSeconds()).isAfter(Instant.now())) {
            return new Listing(true, true, "listado en caché", cachedAt, cached);
        }
        try {
            List<Merchant> fresh = fetch();
            cached = fresh; cachedAt = Instant.now(); lastError = null;
            return new Listing(true, false, "portal de negocios", cachedAt, fresh);
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Merchant portal not reachable: {}", lastError);
            return new Listing(false, cachedAt != null, "portal no responde: " + lastError + (cachedAt != null ? " (se muestra el último listado)" : ""), cachedAt, cached);
        }
    }

    /** A live probe for the third-party registry. */
    public boolean ping() {
        if (!configured()) return false;
        try { fetch(); return true; } catch (Exception e) { lastError = e.getMessage(); return false; }
    }

    public String lastError() { return lastError; }

    public String mode() { return configured() ? "http" : "sin configurar"; }

    private List<Merchant> fetch() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getBaseUrl() + "/merchants?search=" + URLEncoder.encode("", StandardCharsets.UTF_8)))
                .timeout(Duration.ofMillis(settings.getTimeoutMs()))
                .header("Accept", "application/json").header("X-API-Key", settings.getApiKey()).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " del portal");
        JsonNode root = json.readTree(r.body());
        JsonNode data = root.has("data") ? root.get("data") : root;
        List<Merchant> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(new Merchant(n.path("id").isNumber() ? n.get("id").asLong() : null, text(n, "codigoComercio"), text(n, "nombreComercio"),
                        text(n, "rfc"), text(n, "estatus"), n.path("bloqueado").asBoolean(false), text(n, "tipoComercio"), text(n, "subMid")));
            }
        }
        out.sort((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name())));
        return out;
    }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }
}
