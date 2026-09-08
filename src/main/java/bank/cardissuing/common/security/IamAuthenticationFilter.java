package bank.cardissuing.common.security;

import bank.cardissuing.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a bearer token (a person, validated by the IAM for our project) or an API key
 * (a system) into the request's authentication. Permissions become authorities as the
 * IAM names them (CMS:READ, CMS:OPERATE...), roles become ROLE_*. A SUPER_ADMIN of the
 * IAM holds every CMS permission. Introspection answers are cached briefly per token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IamAuthenticationFilter extends OncePerRequestFilter {

    /** Every permission the CMS knows; SUPER_ADMIN and the API key get all of them. */
    public static final List<String> ALL_PERMISSIONS = List.of(Permissions.READ, Permissions.OPERATE, Permissions.FRAUD,
            Permissions.DISPUTES, Permissions.REPORTS, Permissions.ADMIN);

    private final IamSettings settings;
    private final IamClient iam;

    private record Cached(IamClient.Introspection result, Instant until) { }
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** A console logout drops the cached answer so a revoked token stops working at once. */
    public void forget(String token) { if (token != null) cache.remove(token); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!settings.isEnabled() || SecurityContextHolder.getContext().getAuthentication() != null) { chain.doFilter(request, response); return; }
        try {
            String apiKey = request.getHeader("X-Api-Key");
            String auth = request.getHeader("Authorization");
            if (apiKey != null && !apiKey.isBlank()) {
                authenticateApiKey(apiKey);
            } else if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                authenticateBearer(auth.substring(7).trim());
            }
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute("auth.error", e);
        }
        chain.doFilter(request, response);
    }

    private void authenticateApiKey(String key) {
        String expected = settings.getApiKey().getValue();
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, key)) {
            throw new BusinessException("AUTH_BAD_API_KEY", "Llave de API inválida", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        List<GrantedAuthority> auth = new ArrayList<>();
        ALL_PERMISSIONS.forEach(p -> auth.add(new SimpleGrantedAuthority(p)));
        auth.add(new SimpleGrantedAuthority("ROLE_API_CLIENT"));
        set(new CmsPrincipal(settings.getApiKey().getName(), null, Set.of("API_CLIENT"), Set.copyOf(ALL_PERMISSIONS), true), auth);
    }

    private void authenticateBearer(String token) {
        Instant now = Instant.now();
        Cached c = cache.get(token);
        IamClient.Introspection r;
        if (c != null && c.until().isAfter(now)) r = c.result();
        else {
            r = iam.introspect(token);
            cache.put(token, new Cached(r, now.plusSeconds(Math.max(5, settings.getIam().getCacheSeconds()))));
            if (cache.size() > 5000) cache.entrySet().removeIf(e -> e.getValue().until().isBefore(now));
        }
        if (!r.valid()) throw new BusinessException("AUTH_TOKEN_INVALID", "Sesión inválida o vencida", org.springframework.http.HttpStatus.UNAUTHORIZED);
        boolean superAdmin = r.roles().contains("SUPER_ADMIN");
        if (!r.projectAccessGranted() && !superAdmin) {
            throw new BusinessException("AUTH_NO_PROJECT_ACCESS", "El usuario no tiene acceso al proyecto " + settings.getIam().getProjectCode(), org.springframework.http.HttpStatus.FORBIDDEN);
        }
        Set<String> perms = new java.util.LinkedHashSet<>();
        r.permissions().stream().filter(p -> p.startsWith("CMS:")).forEach(perms::add);
        if (superAdmin) perms.addAll(ALL_PERMISSIONS);
        List<GrantedAuthority> auth = new ArrayList<>();
        perms.forEach(p -> auth.add(new SimpleGrantedAuthority(p)));
        r.roles().forEach(role -> auth.add(new SimpleGrantedAuthority("ROLE_" + role)));
        set(new CmsPrincipal(r.username(), r.email(), r.roles(), perms, false), auth);
    }

    private static void set(CmsPrincipal principal, List<GrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken a = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(a);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
