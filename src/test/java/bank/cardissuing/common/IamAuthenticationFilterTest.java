package bank.cardissuing.common;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.CmsPrincipal;
import bank.cardissuing.common.security.IamAuthenticationFilter;
import bank.cardissuing.common.security.IamClient;
import bank.cardissuing.common.security.IamSettings;
import bank.cardissuing.common.security.Permissions;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamAuthenticationFilterTest {

    @Mock IamClient iam;
    @Mock FilterChain chain;
    IamSettings settings = new IamSettings();
    IamAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        settings.getApiKey().setValue("k-secret");
        settings.getApiKey().setName("scripts");
        filter = new IamAuthenticationFilter(settings, iam);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest req(String header, String value) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/cards");
        if (header != null) r.addHeader(header, value);
        return r;
    }

    private static Set<String> authorities() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? Set.of() : a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void noHeader_noAuthentication_chainContinues() throws Exception {
        filter.doFilter(req(null, null), new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(iam);
    }

    @Test
    void apiKey_rightOne_isTheSystemWithEveryPermission() throws Exception {
        filter.doFilter(req("X-Api-Key", "k-secret"), new MockHttpServletResponse(), chain);
        CmsPrincipal p = CmsPrincipal.current().orElseThrow();
        assertTrue(p.system());
        assertEquals("scripts", p.username());
        assertTrue(authorities().containsAll(IamAuthenticationFilter.ALL_PERMISSIONS));
        assertEquals("ops", CmsPrincipal.auditName("ops"));          // a system records the operator it names
        assertEquals("scripts", CmsPrincipal.auditName(null));      // or itself
    }

    @Test
    void apiKey_wrongOrDisabled_isRejected() throws Exception {
        MockHttpServletRequest r = req("X-Api-Key", "nope");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("AUTH_BAD_API_KEY", ((BusinessException) r.getAttribute("auth.error")).getErrorCode());
        settings.getApiKey().setValue("");
        r = req("X-Api-Key", "");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void bearer_validWithProject_getsCmsPermissionsAndRoles_andIsCached() throws Exception {
        when(iam.introspect("t1")).thenReturn(new IamClient.Introspection(true, "mesa", "m@x", true, Set.of("CMS_MESA_CONTROL"), Set.of("CMS:READ", "CMS:OPERATE", "PORTAL:READ")));
        filter.doFilter(req("Authorization", "Bearer t1"), new MockHttpServletResponse(), chain);
        CmsPrincipal p = CmsPrincipal.current().orElseThrow();
        assertFalse(p.system());
        assertEquals("mesa", p.username());
        assertEquals(Set.of("CMS:READ", "CMS:OPERATE", "ROLE_CMS_MESA_CONTROL"), authorities());
        assertEquals("mesa", CmsPrincipal.auditName("alguien.inventado")); // a person is always herself
        SecurityContextHolder.clearContext();
        filter.doFilter(req("Authorization", "Bearer t1"), new MockHttpServletResponse(), chain);
        verify(iam, times(1)).introspect("t1");
        filter.forget("t1");
        SecurityContextHolder.clearContext();
        filter.doFilter(req("Authorization", "Bearer t1"), new MockHttpServletResponse(), chain);
        verify(iam, times(2)).introspect("t1");
    }

    @Test
    void bearer_superAdmin_holdsEverything_evenWithoutProjectAccess() throws Exception {
        when(iam.introspect(anyString())).thenReturn(new IamClient.Introspection(true, "admin", null, false, Set.of("SUPER_ADMIN"), Set.of("ADQUIRENCIA:ADMIN")));
        filter.doFilter(req("Authorization", "Bearer t2"), new MockHttpServletResponse(), chain);
        assertTrue(authorities().containsAll(IamAuthenticationFilter.ALL_PERMISSIONS));
        assertTrue(authorities().contains("ROLE_SUPER_ADMIN"));
        assertFalse(authorities().contains("ADQUIRENCIA:ADMIN"), "other projects' permissions do not leak in");
    }

    @Test
    void bearer_invalid_orWithoutProject_isRejectedWithTheRightCode() throws Exception {
        when(iam.introspect("bad")).thenReturn(new IamClient.Introspection(false, null, null, false, Set.of(), Set.of()));
        MockHttpServletRequest r = req("Authorization", "Bearer bad");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("AUTH_TOKEN_INVALID", ((BusinessException) r.getAttribute("auth.error")).getErrorCode());

        when(iam.introspect("other")).thenReturn(new IamClient.Introspection(true, "op", null, false, Set.of("AGGREGATOR_OPERATOR"), Set.of("PORTAL:WRITE")));
        r = req("Authorization", "Bearer other");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("AUTH_NO_PROJECT_ACCESS", ((BusinessException) r.getAttribute("auth.error")).getErrorCode());
        assertEquals(Permissions.READ, "CMS:READ");
    }

    @Test
    void securityDisabled_skipsEverything() throws Exception {
        settings.setEnabled(false);
        filter.doFilter(req("Authorization", "Bearer whatever"), new MockHttpServletResponse(), chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(iam);
    }
}
