package bank.cardissuing.clearing.infrastructure;

import bank.cardissuing.clearing.domain.ClearingBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, Long> {
    List<ClearingBatch> findTop100ByOrderByCreatedAtDesc();
    Optional<ClearingBatch> findBySha256(String sha256);
    List<ClearingBatch> findBySettlementCycleIdOrderByCreatedAtAsc(Long settlementCycleId);
}
