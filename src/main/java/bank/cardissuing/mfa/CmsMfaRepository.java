package bank.cardissuing.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CmsMfaRepository extends JpaRepository<CmsMfa, String> {
    Optional<CmsMfa> findByUsername(String username);
}
