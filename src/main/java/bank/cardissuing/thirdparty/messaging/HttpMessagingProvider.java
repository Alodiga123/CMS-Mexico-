package bank.cardissuing.thirdparty.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real connection: a REST contract every messaging provider (Twilio, Infobip,
 * MessageBird, a local aggregator) can be mapped to with a thin adapter on their side
 * or ours. {@code POST {base-url}/messages} with a bearer key and a JSON body
 * {channel, to, text, ref, sender}; the answer carries {id, status}.
 * See docs/contratos-terceros.md for what to ask the provider for.
 */
@Component
@ConditionalOnProperty(name = "thirdparty.messaging.mode", havingValue = "http")
@Slf4j
public class HttpMessagingProvider implements MessagingProvider {

    private final MessagingSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;

    public HttpMessagingProvider(MessagingSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.getTimeoutMs())).build();
        log.info("Messaging provider: HTTP at {} (sender {})", settings.getBaseUrl(), settings.getSenderId());
    }

    @Override public String mode() { return "http"; }

    @Override
    public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getBaseUrl() + "/health"))
                    .timeout(Duration.ofMillis(settings.getTimeoutMs())).header("Authorization", "Bearer " + settings.getApiKey()).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Health(r.statusCode() / 100 == 2, "HTTP " + r.statusCode());
        } catch (IOException | RuntimeException e) {
            return new Health(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Health(false, "interrupted");
        }
    }

    @Override
    public Delivery send(Message m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", m.channel().name());
        body.put("to", m.to());
        body.put("text", m.text());
        body.put("ref", m.ref());
        body.put("sender", settings.getSenderId());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getBaseUrl() + "/messages"))
                    .timeout(Duration.ofMillis(settings.getTimeoutMs()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("messaging provider HTTP " + r.statusCode() + ": " + r.body());
            JsonNode n = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            return new Delivery(n.path("id").asText(null), n.path("status").asText("SENT"));
        } catch (IOException e) {
            throw new IllegalStateException("messaging provider unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
