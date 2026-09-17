package bank.cardissuing.mfa;

import bank.cardissuing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Segundo factor (TOTP) de la consola del CMS (A3, PCI DSS 8.4.2/8.5). Guarda un secreto por usuario
 * (cifrado) y, tras validar la contraseña en el IAM, exige un código de 6 dígitos antes de emitir la
 * sesión. Los "challenges" (post-contraseña, pre-OTP) viven en memoria con TTL corto (una instancia).
 *
 * Se activa con cms.mfa.enabled=true. Apagado (por defecto) el login se comporta como antes, para no
 * dejar fuera a la consola hasta que todos los usuarios hayan hecho el alta del segundo factor.
 */
@Service
@RequiredArgsConstructor
public class MfaService {

    @Value("${cms.mfa.enabled:false}")
    private boolean enabled;
    @Value("${cms.mfa.issuer:CMS Mexico}")
    private String issuer;
    @Value("${cms.mfa.challenge-ttl-seconds:300}")
    private long challengeTtlSeconds;

    private final CmsMfaRepository repo;
    private final MfaSecretCipher cipher;

    private record Pending(String username, Map<String, Object> session, String pendingSecret, long expiresAt) { }
    private final Map<String, Pending> challenges = new ConcurrentHashMap<>();

    public boolean isEnabled() { return enabled; }

    /** ¿El usuario ya tiene el segundo factor dado de alta y activo? */
    public boolean isEnrolled(String username) {
        return repo.findByUsername(username).map(CmsMfa::isEnabled).orElse(false);
    }

    /**
     * Crea un challenge tras validar la contraseña. Si el usuario ya tiene TOTP, se le pedirá el
     * código; si no, se genera un secreto nuevo para que lo dé de alta. Devuelve el cuerpo que la
     * consola necesita (challenge, y en el alta el secreto y el URI otpauth).
     */
    public Map<String, Object> startChallenge(String username, Map<String, Object> session) {
        purgeExpired();
        String id = UUID.randomUUID().toString();
        long exp = System.currentTimeMillis() + challengeTtlSeconds * 1000;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mfaRequired", true);
        out.put("mfaChallenge", id);
        if (isEnrolled(username)) {
            challenges.put(id, new Pending(username, session, null, exp));
            out.put("mfaSetupRequired", false);
        } else {
            String secret = Totp.newSecret();
            challenges.put(id, new Pending(username, session, secret, exp));
            out.put("mfaSetupRequired", true);
            out.put("secret", secret);
            out.put("otpauthUri", Totp.otpauthUri(issuer, username, secret));
        }
        return out;
    }

    /** Verifica el código de un usuario YA dado de alta y devuelve la sesión. */
    public Map<String, Object> verify(String challenge, String code) {
        Pending p = take(challenge);
        CmsMfa m = repo.findByUsername(p.username())
                .filter(CmsMfa::isEnabled)
                .orElseThrow(() -> new BusinessException("MFA_NOT_ENROLLED", "El usuario no tiene segundo factor", HttpStatus.BAD_REQUEST));
        if (!Totp.verify(cipher.decrypt(m.getSecretEnc()), code)) {
            challenges.put(challenge, p); // permitir reintento dentro del TTL
            throw new BusinessException("MFA_INVALID_CODE", "Código de verificación incorrecto", HttpStatus.UNAUTHORIZED);
        }
        return complete(p.session());
    }

    /** Alta: verifica el código contra el secreto pendiente, lo activa y devuelve la sesión. */
    @Transactional
    public Map<String, Object> enable(String challenge, String code) {
        Pending p = take(challenge);
        if (p.pendingSecret() == null) throw new BusinessException("MFA_NO_PENDING_SETUP", "No hay alta pendiente", HttpStatus.BAD_REQUEST);
        if (!Totp.verify(p.pendingSecret(), code)) {
            challenges.put(challenge, p);
            throw new BusinessException("MFA_INVALID_CODE", "Código de verificación incorrecto", HttpStatus.UNAUTHORIZED);
        }
        CmsMfa m = repo.findByUsername(p.username()).orElseGet(CmsMfa::new);
        m.setUsername(p.username());
        m.setSecretEnc(cipher.encrypt(p.pendingSecret()));
        m.setEnabled(true);
        if (m.getCreatedAt() == null) m.setCreatedAt(LocalDateTime.now());
        m.setEnabledAt(LocalDateTime.now());
        repo.save(m);
        return complete(p.session());
    }

    private Map<String, Object> complete(Map<String, Object> session) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(session);
        out.put("mfaSatisfied", true);
        return out;
    }

    private Pending take(String challenge) {
        purgeExpired();
        Pending p = challenge == null ? null : challenges.remove(challenge);
        if (p == null || p.expiresAt() < System.currentTimeMillis())
            throw new BusinessException("MFA_CHALLENGE_INVALID", "Sesión de verificación expirada; inicia sesión de nuevo", HttpStatus.UNAUTHORIZED);
        return p;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }
}
