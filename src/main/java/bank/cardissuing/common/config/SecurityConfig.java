package bank.cardissuing.common.config;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.IamAuthenticationFilter;
import bank.cardissuing.common.security.IamSettings;
import bank.cardissuing.common.security.Permissions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who may call what. The console (static files), the API docs and the login endpoint are
 * open; every /api route needs a person authenticated by the IAM with the right CMS
 * permission, or the system API key. Answers are JSON 401 / 403 like the rest of the API.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final IamSettings settings;
    private final IamAuthenticationFilter iamFilter;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // Cabeceras de seguridad (PCI DSS 6.4.1 / OWASP). La CSP tolera el inline de la consola
            // (SPA con JS/CSS embebido); endurecerla (quitar 'unsafe-inline'/'unsafe-eval') es un paso
            // posterior con nonces. HSTS solo viaja sobre HTTPS (TLS termina en el reverse proxy).
            .headers(h -> h
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .frameOptions(f -> f.sameOrigin())
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                    "font-src 'self' https://fonts.gstatic.com data:; " +
                    "img-src 'self' data:; connect-src 'self'; " +
                    "frame-ancestors 'self'; base-uri 'self'; object-src 'none'")));

        if (!settings.isEnabled()) {
            log.warn("SECURITY DISABLED (security.enabled=false): every endpoint is open. Development only.");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.addFilterBefore(iamFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> reject(req, res, 401, "AUTH_REQUIRED", "Inicia sesión para usar la API"))
                .accessDeniedHandler((req, res, ex) -> reject(req, res, 403, "AUTH_FORBIDDEN", "No tienes permiso para esta operación")))
            .authorizeHttpRequests(auth -> auth
                // console, docs, login
                .requestMatchers("/", "/index.html", "/ops/**", "/theme/**", "/favicon.ico", "/error",
                        "/swagger-ui.html", "/swagger-ui/**", "/api-docs", "/api-docs/**", "/api/auth/**").permitAll()
                // rotación/estado de la llave de la bóveda de PAN: operación de administración
                .requestMatchers("/api/cards/pan/**").hasAuthority(Permissions.ADMIN)
                // administration: products, promotions, HSM keys
                .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/promotions/**", "/api/hsm/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/products/**", "/api/promotions/**", "/api/hsm/**").hasAuthority(Permissions.ADMIN)
                // the breaker in front of the core: administration only
                .requestMatchers(HttpMethod.GET, "/api/core/**", "/api/iso/**", "/api/standin/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/core/**").hasAuthority(Permissions.ADMIN)
                // fraud and industry antifraud
                .requestMatchers(HttpMethod.GET, "/api/fraud/**", "/api/guild/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/fraud/**", "/api/guild/**").hasAuthority(Permissions.FRAUD)
                // disputes
                .requestMatchers(HttpMethod.GET, "/api/disputes/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/disputes/**").hasAuthority(Permissions.DISPUTES)
                // reports: anyone may look, sealing a run is a duty
                .requestMatchers(HttpMethod.GET, "/api/reports/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/reports/**").hasAuthority(Permissions.REPORTS)
                // audit trail
                .requestMatchers("/api/audit/**").hasAuthority(Permissions.READ)
                // everything operational: cards, customers, controls, plastics, holds, ledger, reconciliation
                .requestMatchers(HttpMethod.PUT, "/api/thirdparties/**").hasAuthority(Permissions.ADMIN)
                .requestMatchers(HttpMethod.GET, "/api/**").hasAuthority(Permissions.READ)
                .requestMatchers("/api/**").hasAuthority(Permissions.OPERATE)
                .anyRequest().permitAll());
        return http.build();
    }

    private void reject(HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        Object cause = req.getAttribute("auth.error");
        if (cause instanceof BusinessException be) { status = be.getHttpStatus().value(); code = be.getErrorCode(); message = be.getMessage(); }
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", code);
        body.put("message", message);
        body.put("path", req.getRequestURI());
        body.put("timestamp", LocalDateTime.now().toString());
        res.getWriter().write(json.writeValueAsString(body));
    }
}
