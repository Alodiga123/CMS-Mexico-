package bank.cardissuing.standin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: place in the core the reservations the CMS approved on its own while the core was down. */
@Component
@RequiredArgsConstructor
@Slf4j
public class StandInSettleJob {

    private final StandInService standIn;

    @Scheduled(fixedDelayString = "${standin.settle-interval-ms:60000}", initialDelayString = "${standin.settle-initial-delay-ms:30000}")
    public void settle() {
        try {
            int n = standIn.settlePending();
            if (n > 0) log.info("Stand-in: {} reservation(s) settled with the core", n);
        } catch (Exception e) {
            log.warn("Stand-in settlement job failed: {}", e.getMessage());
        }
    }
}
