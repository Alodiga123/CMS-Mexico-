package bank.cardissuing.funds.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles every linked core account on a fixed delay. Off with
 * {@code reconciliation.enabled=false}. Scheduling itself is enabled by the funds
 * module's SchedulingConfig.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private final ReconciliationService service;

    @Scheduled(fixedDelayString = "${reconciliation.interval-ms:900000}",
               initialDelayString = "${reconciliation.initial-delay-ms:60000}")
    public void run() {
        service.reconcileAll();
    }
}
