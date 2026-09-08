package bank.cardissuing.funds.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, Long> {

    Optional<ReconciliationItem> findByItemKey(String itemKey);

    List<ReconciliationItem> findByAccountIdAndStatus(String accountId, ReconciliationItem.Status status);

    List<ReconciliationItem> findByStatusOrderByCreatedAtDesc(ReconciliationItem.Status status);

    List<ReconciliationItem> findAllByOrderByCreatedAtDesc();

    long countByStatus(ReconciliationItem.Status status);
}
