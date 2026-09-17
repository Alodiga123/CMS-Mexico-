package bank.cardissuing.common.security;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The console's door. Login goes to the IAM on the browser's behalf (no CORS, no IAM URL in
 * the page), comes back with the token and the CMS permissions; /me tells a page who it is;
 * logout forgets the cached introspection so the token is re-checked at once.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IamClient iam;
    private final IamSettings settings;
    private final IamAuthenticationFilter filter;
    private final AuditService audit;
    private final LoginRateLimiter rateLimiter;
    private final bank.cardissuing.mfa.MfaService mfa;

    public record LoginBody(String username, String password) { }
    public record MfaBody(String challenge, String code) { }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginBody b) {
        if (b == null || b.username() == null || b.username().isBlank() || b.password() == null || b.password().isBlank()) {
            throw new BusinessException("AUTH_INVALID_CREDENTIALS", "Usuario y contraseña son obligatorios", HttpStatus.BAD_REQUEST);
        }
        // Bloqueo por intentos en el propio CMS (PCI DSS 8.3.4), además del que aplique el IAM.
        // Se bloquea por USUARIO (control por cuenta): no por IP, porque detrás del reverse proxy
        // muchos usuarios pueden compartir la misma IP de origen y unos pocos fallos bloquearían a
        // todos. Habilitar el bloqueo por IP solo cuando el proxy de borde propague la IP real.
        String user = b.username().trim();
        rateLimiter.assertNotBlocked(user);
        IamClient.Login l;
        try {
            l = iam.login(user, b.password());
        } catch (BusinessException e) {
            // Solo cuenta como intento fallido el rechazo de credenciales, no una caída del IAM.
            if (e.getHttpStatus() == HttpStatus.UNAUTHORIZED) rateLimiter.recordFailure(user);
            throw e;
        }
        rateLimiter.reset(user);
        IamClient.Introspection i = iam.introspect(l.accessToken());
        boolean superAdmin = i.roles().contains("SUPER_ADMIN");
        if (!i.projectAccessGranted() && !superAdmin) {
            audit.log("LOGIN_DENIED_NO_PROJECT", "User", l.username(), l.username());
            throw new BusinessException("AUTH_NO_PROJECT_ACCESS", "Tu usuario no tiene acceso al proyecto " + settings.getIam().getProjectCode(), HttpStatus.FORBIDDEN);
        }
        Set<String> perms = new java.util.LinkedHashSet<>();
        i.permissions().stream().filter(p -> p.startsWith("CMS:")).forEach(perms::add);
        if (superAdmin) perms.addAll(IamAuthenticationFilter.ALL_PERMISSIONS);
        audit.log("LOGIN", "User", l.username(), l.username());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", l.accessToken());
        m.put("refreshToken", l.refreshToken());
        m.put("expiresIn", l.expiresIn());
        m.put("username", l.username());
        m.put("email", l.email());
        m.put("fullName", ((l.firstName() != null ? l.firstName() : "") + " " + (l.lastName() != null ? l.lastName() : "")).trim());
        m.put("roles", i.roles());
        m.put("permissions", perms);
        m.put("project", settings.getIam().getProjectCode());
        m.put("userId", l.userId());
        // the IAM marks accounts created with a temporary password; the console asks for a new one before letting the user in
        m.put("mustChangePassword", iam.mustChangePassword(l.accessToken(), l.userId()));
        // Segundo factor (A3): con MFA activo la contraseña sola no basta; se devuelve un challenge y
        // el token real solo se emite tras verificar el código TOTP (o darlo de alta la primera vez).
        if (mfa.isEnabled()) return ResponseEntity.ok(mfa.startChallenge(l.username(), m));
        return ResponseEntity.ok(m);
    }

    /** Segundo paso del login con MFA: verifica el código TOTP de un usuario ya dado de alta. */
    @PostMapping("/mfa/verify")
    public ResponseEntity<Map<String, Object>> mfaVerify(@RequestBody MfaBody b) {
        Map<String, Object> s = mfa.verify(b == null ? null : b.challenge(), b == null ? null : b.code());
        audit.log("LOGIN_MFA", "User", String.valueOf(s.get("username")), String.valueOf(s.get("username")));
        return ResponseEntity.ok(s);
    }

    /** Alta del segundo factor: verifica el primer código contra el secreto pendiente y lo activa. */
    @PostMapping("/mfa/enable")
    public ResponseEntity<Map<String, Object>> mfaEnable(@RequestBody MfaBody b) {
        Map<String, Object> s = mfa.enable(b == null ? null : b.challenge(), b == null ? null : b.code());
        audit.log("MFA_ENROLLED", "User", String.valueOf(s.get("username")), String.valueOf(s.get("username")));
        return ResponseEntity.ok(s);
    }

    public record ChangePasswordBody(Long userId, String currentPassword, String newPassword) { }

    /** The user changes their own IAM password from the console; the IAM verifies the current one. */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordBody b,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        CmsPrincipal p = CmsPrincipal.current().orElseThrow(() -> new BusinessException("AUTH_REQUIRED", "Inicia sesion", HttpStatus.UNAUTHORIZED));
        // El IAM exige mínimo 12 caracteres (AuthService.MIN_PASSWORD_LENGTH); validar aquí lo mismo
        // evita que el CMS acepte una clave de 10-11 y luego el IAM la rechace con "cambio rechazado".
        if (b == null || b.currentPassword() == null || b.newPassword() == null || b.newPassword().length() < 12) {
            throw new BusinessException("AUTH_PASSWORD_INVALID", "La contrasena nueva debe tener al menos 12 caracteres", HttpStatus.BAD_REQUEST);
        }
        if (b.newPassword().equals(b.currentPassword())) {
            throw new BusinessException("AUTH_PASSWORD_INVALID", "La contrasena nueva debe ser distinta de la actual", HttpStatus.BAD_REQUEST);
        }
        // El IAM exige el Bearer del propio usuario y saca el userId del JWT (ver IamClient.changePassword).
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BusinessException("AUTH_REQUIRED", "Inicia sesion", HttpStatus.UNAUTHORIZED);
        }
        iam.changePassword(authHeader.substring(7).trim(), b.currentPassword(), b.newPassword());
        audit.log("PASSWORD_CHANGED", "User", p.username(), p.username());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        CmsPrincipal p = CmsPrincipal.current().orElseThrow(() -> new BusinessException("AUTH_REQUIRED", "Inicia sesión", HttpStatus.UNAUTHORIZED));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", p.username());
        m.put("email", p.email());
        m.put("roles", p.roles());
        m.put("permissions", p.permissions());
        m.put("system", p.system());
        m.put("project", settings.getIam().getProjectCode());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) filter.forget(auth.substring(7).trim());
        CmsPrincipal.current().ifPresent(p -> audit.log("LOGOUT", "User", p.username(), p.username()));
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
