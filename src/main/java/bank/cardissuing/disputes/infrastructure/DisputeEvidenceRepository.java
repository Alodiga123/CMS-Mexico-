package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.domain.DisputeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {
    List<DisputeEvidence> findByDisputeOrderByCreatedAtAsc(Dispute dispute);
}
