package bank.cardissuing.common.security;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueo por intentos fallidos de login en el propio CMS (PCI DSS 8.3.4), además del que aplique
 * el IAM. Ventana deslizante en memoria por clave (usuario y también IP): tras {@code maxAttempts}
 * fallos dentro de {@code windowSeconds} se bloquea la clave por {@code blockSeconds} y el login
 * responde 429. Un login exitoso limpia el contador. En memoria (una instancia); si el CMS se
 * escala a varias réplicas, mover a Redis.
 */
@Component
public class LoginRateLimiter {

    @Value("${auth.lockout.max-attempts:5}")
    private int maxAttempts;
    @Value("${auth.lockout.window-seconds:900}")
    private long windowSeconds;
    @Value("${auth.lockout.block-seconds:900}")
    private long blockSeconds;

    private static final class Counter {
        int fails;
        long windowStartMs;
        long blockedUntilMs;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Rechaza con 429 si la clave está bloqueada. Llamar antes de validar credenciales. */
    public void assertNotBlocked(String key) {
        if (key == null || key.isBlank()) return;
        Counter c = counters.get(key.toLowerCase());
        if (c == null) return;
        long now = System.currentTimeMillis();
        synchronized (c) {
            if (c.blockedUntilMs > now) {
                long retry = (c.blockedUntilMs - now) / 1000 + 1;
                throw new BusinessException("AUTH_TOO_MANY_ATTEMPTS",
                        "Demasiados intentos. Espera " + retry + " segundos e inténtalo de nuevo.",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        }
    }

    /** Registra un fallo de credenciales; bloquea la clave si supera el umbral en la ventana. */
    public void recordFailure(String key) {
        if (key == null || key.isBlank()) return;
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(key.toLowerCase(), k -> new Counter());
        synchronized (c) {
            if (c.windowStartMs == 0 || now - c.windowStartMs > windowSeconds * 1000) {
                c.windowStartMs = now;
                c.fails = 0;
            }
            c.fails++;
            if (c.fails >= maxAttempts) {
                c.blockedUntilMs = now + blockSeconds * 1000;
            }
        }
    }

    /** Limpia el contador tras un login exitoso. */
    public void reset(String key) {
        if (key != null && !key.isBlank()) counters.remove(key.toLowerCase());
    }
}
