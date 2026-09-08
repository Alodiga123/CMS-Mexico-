package bank.cardissuing.thirdparty.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {
    Page<OutboundMessage> findAllByOrderByCreatedAtDesc(Pageable p);
    Page<OutboundMessage> findByStatusOrderByCreatedAtDesc(OutboundMessage.Status status, Pageable p);
    Page<OutboundMessage> findByCardIdOrderByCreatedAtDesc(Long cardId, Pageable p);
    List<OutboundMessage> findTop100ByStatusOrderByCreatedAtAsc(OutboundMessage.Status status);
    List<OutboundMessage> findByBusinessRefOrderByCreatedAtDesc(String businessRef);
    long countByStatus(OutboundMessage.Status status);
}
