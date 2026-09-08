package bank.cardissuing.fraud.infrastructure;

import bank.cardissuing.fraud.domain.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.Status status);

    List<FraudAlert> findAllByOrderByCreatedAtDesc();

    boolean existsByMerchantIdAndTypeAndStatusAndCreatedAtAfter(String merchantId, FraudAlert.Type type,
                                                                FraudAlert.Status status, LocalDateTime since);
}
