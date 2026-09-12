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

    public record LoginBody(String username, String password) { }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginBody b) {
        if (b == null || b.username() == null || b.username().isBlank() || b.password() == null || b.password().isBlank()) {
            throw new BusinessException("AUTH_INVALID_CREDENTIALS", "Usuario y contraseña son obligatorios", HttpStatus.BAD_REQUEST);
        }
        IamClient.Login l = iam.login(b.username().trim(), b.password());
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
        return ResponseEntity.ok(m);
    }

    public record ChangePasswordBody(Long userId, String currentPassword, String newPassword) { }

    /** The user changes their own IAM password from the console; the IAM verifies the current one. */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordBody b) {
        CmsPrincipal p = CmsPrincipal.current().orElseThrow(() -> new BusinessException("AUTH_REQUIRED", "Inicia sesion", HttpStatus.UNAUTHORIZED));
        if (b == null || b.userId() == null || b.currentPassword() == null || b.newPassword() == null || b.newPassword().length() < 10) {
            throw new BusinessException("AUTH_PASSWORD_INVALID", "La contrasena nueva debe tener al menos 10 caracteres", HttpStatus.BAD_REQUEST);
        }
        if (b.newPassword().equals(b.currentPassword())) {
            throw new BusinessException("AUTH_PASSWORD_INVALID", "La contrasena nueva debe ser distinta de la actual", HttpStatus.BAD_REQUEST);
        }
        iam.changePassword(b.userId(), b.currentPassword(), b.newPassword());
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
