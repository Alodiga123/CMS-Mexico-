package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.fraud.guild.domain.GuildVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildVerificationRepository extends JpaRepository<GuildVerification, Long> {
    Optional<GuildVerification> findBySubjectTypeAndSubject(GuildVerification.Subject subjectType, String subject);
    List<GuildVerification> findTop200ByOrderByCheckedAtDesc();
}
