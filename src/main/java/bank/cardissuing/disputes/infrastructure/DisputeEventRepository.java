package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.domain.DisputeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeEventRepository extends JpaRepository<DisputeEvent, Long> {
    List<DisputeEvent> findByDisputeOrderByCreatedAtAsc(Dispute dispute);
}
