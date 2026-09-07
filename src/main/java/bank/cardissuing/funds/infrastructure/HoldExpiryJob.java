package bank.cardissuing.funds.infrastructure;

import bank.cardissuing.funds.application.HoldExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs hold expiry on a fixed delay. Off with {@code holds.expiry.enabled=false}
 * (e.g. in tests or when another instance owns the job).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "holds.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {

    private final HoldExpiryService expiry;

    @Scheduled(fixedDelayString = "${holds.expiry.interval-ms:60000}",
               initialDelayString = "${holds.expiry.initial-delay-ms:30000}")
    public void run() {
        expiry.expireDue();
    }
}
