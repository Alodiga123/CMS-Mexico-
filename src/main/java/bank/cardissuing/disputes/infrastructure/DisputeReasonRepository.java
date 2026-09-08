package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.disputes.domain.DisputeReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeReasonRepository extends JpaRepository<DisputeReason, Long> {
    Optional<DisputeReason> findByCode(String code);
    List<DisputeReason> findByActiveTrueOrderByCode();
}
