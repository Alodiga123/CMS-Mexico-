package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.fraud.guild.application.GuildClient;
import bank.cardissuing.fraud.guild.application.GuildSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The real channel to the guild: HTTPS with our client certificate (mTLS through a
 * Spring SSL bundle), every request signed with HMAC-SHA256 over timestamp, method,
 * path and body, tight timeouts. The message shapes follow docs/guild-antifraude.md;
 * they are the CMS's proposal until the guild's own specification replaces them.
 */
@Component
@ConditionalOnProperty(name = "guild.mode", havingValue = "http")
@Slf4j
public class HttpGuildClient implements GuildClient {

    private final GuildSettings settings;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public HttpGuildClient(GuildSettings settings, ObjectProvider<SslBundles> bundles) {
        this.settings = settings;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.getTimeoutMs()));
        if (settings.getSslBundle() != null && !settings.getSslBundle().isBlank()) {
            SslBundles sb = bundles.getIfAvailable();
            if (sb == null) throw new IllegalStateException("guild.ssl-bundle is set but no SSL bundles are configured");
            b.sslContext(sb.getBundle(settings.getSslBundle()).createSslContext());
            log.info("Guild client: mTLS with SSL bundle '{}'", settings.getSslBundle());
        } else {
            log.warn("Guild client: no SSL bundle configured, connecting without a client certificate");
        }
        this.http = b.build();
        log.info("Guild client: HTTP to {} as participant {}", settings.getBaseUrl(), settings.getParticipantId());
    }

    @Override public String mode() { return "http"; }

    @Override
    public Health health() {
        try {
            JsonNode n = call("GET", "/v1/health", null);
            return new Health(true, n.path("status").asText("ok"));
        } catch (Exception e) {
            return new Health(false, e.getMessage());
        }
    }

    @Override
    public Hit verifyCard(String bin, String last4) {
        ObjectNode body = json.createObjectNode().put("bin", bin).put("last4", last4);
        return hit(call("POST", "/v1/verify/card", body));
    }

    @Override
    public Hit verifyMerchant(String merchantId) {
        ObjectNode body = json.createObjectNode().put("merchantId", merchantId);
        return hit(call("POST", "/v1/verify/merchant", body));
    }

    @Override
    public String send(Outbound m) {
        ObjectNode body = json.createObjectNode()
                .put("localRef", m.localRef()).put("type", m.type()).put("bin", m.bin()).put("last4", m.last4())
                .put("merchantId", m.merchantId()).put("merchantName", m.merchantName())
                .put("description", m.description()).put("externalRef", m.externalRef());
        if (m.amount() != null) body.put("amount", m.amount());
        String path = "CHARGEBACK_PREVENTION".equals(m.type()) ? "/v1/chargebacks/prevent" : "/v1/alerts";
        JsonNode n = call("POST", path, body);
        String folio = n.path("folio").asText(null);
        if (folio == null || folio.isBlank()) throw new IllegalStateException("guild answered without a folio: " + n);
        return folio;
    }

    @Override
    public List<Inbound> fetchInbound(LocalDateTime since) {
        String q = since != null ? "?since=" + since.toString() : "";
        JsonNode n = call("GET", "/v1/alerts/inbound" + q, null);
        List<Inbound> out = new ArrayList<>();
        for (JsonNode a : n.path("alerts")) {
            LocalDateTime issued;
            try { issued = OffsetDateTime.parse(a.path("issuedAt").asText()).toLocalDateTime(); }
            catch (Exception e) { issued = LocalDateTime.now(); }
            out.add(new Inbound(a.path("folio").asText(), a.path("type").asText("OTHER"), text(a, "bin"), text(a, "last4"),
                    text(a, "merchantId"), text(a, "merchantName"), text(a, "description"), issued, text(a, "source")));
        }
        return out;
    }

    // ---- transport ----

    private JsonNode call(String method, String path, JsonNode body) {
        try {
            String payload = body != null ? json.writeValueAsString(body) : "";
            String ts = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(settings.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(settings.getTimeoutMs()))
                    .header("Accept", "application/json")
                    .header("X-Participant-Id", settings.getParticipantId())
                    .header("X-Api-Key", settings.getApiKey())
                    .header("X-Timestamp", ts)
                    .header("X-Signature", sign(settings.getHmacSecret(), ts, method, path, payload));
            if (body != null) rb.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(payload));
            else rb.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("guild " + method + " " + path + " -> HTTP " + r.statusCode() + " " + r.body());
            return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new IllegalStateException("guild " + method + " " + path + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("guild call interrupted", e);
        }
    }

    /** HMAC-SHA256 over "timestamp.METHOD.path.body", hex. */
    static String sign(String secret, String timestamp, String method, String path, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((timestamp + "." + method + "." + path + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static Hit hit(JsonNode n) {
        boolean listed = n.path("listed").asBoolean(false);
        LocalDateTime at = null;
        if (n.hasNonNull("listedAt")) { try { at = OffsetDateTime.parse(n.get("listedAt").asText()).toLocalDateTime(); } catch (Exception ignored) { } }
        return new Hit(listed, text(n, "folio"), text(n, "reason"), at);
    }

    private static String text(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }
}
