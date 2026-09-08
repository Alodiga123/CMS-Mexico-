package bank.cardissuing.fraud.infrastructure;

import bank.cardissuing.fraud.domain.StepUpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StepUpChallengeRepository extends JpaRepository<StepUpChallenge, Long> {
    Optional<StepUpChallenge> findByToken(String token);
}
