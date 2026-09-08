package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.disputes.application.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flags live disputes that missed their current deadline. Off with
 * {@code disputes.deadline-check.enabled=false}. Scheduling is enabled by the funds
 * module's SchedulingConfig.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "disputes.deadline-check.enabled", havingValue = "true", matchIfMissing = true)
public class DisputeDeadlineJob {

    private final DisputeService service;

    @Scheduled(fixedDelayString = "${disputes.deadline-check.interval-ms:3600000}",
               initialDelayString = "${disputes.deadline-check.initial-delay-ms:120000}")
    public void run() {
        service.flagBreached();
    }
}
