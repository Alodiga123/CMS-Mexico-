package bank.cardissuing.plastics.infrastructure;

import bank.cardissuing.plastics.domain.PlasticBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlasticBatchRepository extends JpaRepository<PlasticBatch, Long> {
    Optional<PlasticBatch> findByBatchNumber(String batchNumber);
    List<PlasticBatch> findAllByOrderByCreatedAtDesc();
    long countByCreatedAtAfter(LocalDateTime since);
}
