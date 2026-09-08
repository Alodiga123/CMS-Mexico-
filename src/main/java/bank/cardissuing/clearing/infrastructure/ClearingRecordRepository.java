package bank.cardissuing.clearing.infrastructure;

import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.ClearingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClearingRecordRepository extends JpaRepository<ClearingRecord, Long> {
    List<ClearingRecord> findByBatchOrderByLineNoAsc(ClearingBatch batch);
    List<ClearingRecord> findByBatchAndOutcomeOrderByLineNoAsc(ClearingBatch batch, ClearingRecord.Outcome outcome);
    List<ClearingRecord> findTop200ByOutcomeInOrderByCreatedAtDesc(java.util.Collection<ClearingRecord.Outcome> outcomes);
    long countByBatchAndOutcomeNotIn(ClearingBatch batch, java.util.Collection<ClearingRecord.Outcome> outcomes);
}
