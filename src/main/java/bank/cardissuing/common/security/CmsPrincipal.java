package bank.cardissuing.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

/** Who is calling: a person from the IAM or a system with the API key. */
public record CmsPrincipal(String username, String email, Set<String> roles, Set<String> permissions, boolean system) {

    public static Optional<CmsPrincipal> current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof CmsPrincipal p ? Optional.of(p) : Optional.empty();
    }

    /**
     * The name to write in the audit trail: a person is always herself, whatever the request
     * claimed; a system client records the operator it says it acts for, or its own name.
     */
    public static String auditName(String claimed) {
        return current().map(p -> p.system() ? (claimed != null && !claimed.isBlank() ? claimed : p.username()) : p.username())
                .orElse(claimed != null && !claimed.isBlank() ? claimed : "SYSTEM");
    }
}
