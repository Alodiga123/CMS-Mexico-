package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.disputes.domain.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByCardOrderByCreatedAtDesc(Card card);

    List<Dispute> findByStatusOrderByCreatedAtDesc(Dispute.Status status);

    List<Dispute> findByStatusInOrderByCreatedAtAsc(Collection<Dispute.Status> statuses);

    List<Dispute> findAllByOrderByCreatedAtDesc();

    boolean existsByApprovalCodeAndStatusIn(String approvalCode, Collection<Dispute.Status> statuses);
}
