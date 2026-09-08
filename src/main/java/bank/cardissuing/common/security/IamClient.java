package bank.cardissuing.common.security;

import bank.cardissuing.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Talks to the corporate IAM: login on behalf of the console, and introspection of bearer tokens. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IamClient {

    private final IamSettings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** What the IAM says about a token, for our project. */
    public record Introspection(boolean valid, String username, String email, boolean projectAccessGranted,
                                Set<String> roles, Set<String> permissions) { }

    /** The IAM's login answer, passed through to the console. */
    public record Login(String accessToken, String refreshToken, long expiresIn, String username, String email,
                        String firstName, String lastName, Set<String> roles, Set<String> assignedProjects) { }

    public Login login(String username, String password) {
        JsonNode n;
        try {
            n = post("/auth/login", Map.of("username", username, "password", password));
        } catch (IamHttpException e) {
            if (e.status == 401 || e.status == 403 || e.status == 400) {
                throw new BusinessException("AUTH_INVALID_CREDENTIALS", messageOf(e.body, "Usuario o contraseña incorrectos"), HttpStatus.UNAUTHORIZED);
            }
            throw new BusinessException("AUTH_IAM_ERROR", "El IAM respondió " + e.status, HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new Login(n.path("accessToken").asText(null), n.path("refreshToken").asText(null), n.path("expiresIn").asLong(0),
                n.path("username").asText(null), n.path("email").asText(null), n.path("firstName").asText(null), n.path("lastName").asText(null),
                strings(n.path("roles")), strings(n.path("assignedProjects")));
    }

    public Introspection introspect(String token) {
        JsonNode n;
        try {
            n = post("/auth/introspect", Map.of("token", token, "projectCode", settings.getIam().getProjectCode()));
        } catch (IamHttpException e) {
            throw new BusinessException("AUTH_IAM_ERROR", "El IAM respondió " + e.status, HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new Introspection(n.path("valid").asBoolean(false), n.path("username").asText(null), n.path("email").asText(null),
                n.path("projectAccessGranted").asBoolean(false), strings(n.path("roles")), strings(n.path("permissions")));
    }

    // ---- transport ----

    private static final class IamHttpException extends RuntimeException {
        final int status; final String body;
        IamHttpException(int status, String body) { super("IAM HTTP " + status); this.status = status; this.body = body; }
    }

    private JsonNode post(String path, Map<String, String> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getIam().getBaseUrl() + path))
                    .timeout(Duration.ofMillis(settings.getIam().getTimeoutMs()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IamHttpException(r.statusCode(), r.body());
            return json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
        } catch (IOException e) {
            log.warn("IAM unreachable at {}: {}", settings.getIam().getBaseUrl(), e.getMessage());
            throw new BusinessException("AUTH_IAM_UNAVAILABLE", "No se pudo contactar al IAM", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AUTH_IAM_UNAVAILABLE", "Llamada al IAM interrumpida", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String messageOf(String body, String dflt) {
        try { JsonNode n = json.readTree(body); return n.hasNonNull("message") ? n.get("message").asText() : dflt; } catch (Exception e) { return dflt; }
    }

    private static Set<String> strings(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        if (arr != null && arr.isArray()) arr.forEach(x -> out.add(x.asText()));
        return out;
    }
}
