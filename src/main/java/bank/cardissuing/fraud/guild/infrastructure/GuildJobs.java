package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.fraud.guild.application.GuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The three clocks of the guild connection: send the outbox, fetch inbound alerts, expire the overdue. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuildJobs {

    private final GuildService guild;

    @Scheduled(fixedDelayString = "${guild.outbox-interval-ms:30000}", initialDelayString = "${guild.outbox-initial-delay-ms:20000}")
    public void flushOutbox() {
        try {
            int n = guild.flushOutbox();
            if (n > 0) log.info("Guild outbox: {} alert(s) sent", n);
        } catch (Exception e) {
            log.warn("Guild outbox job failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${guild.inbound-interval-ms:300000}", initialDelayString = "${guild.inbound-initial-delay-ms:60000}")
    public void pollInbound() {
        try {
            int n = guild.pollInbound();
            if (n > 0) log.info("Guild inbound: {} new alert(s)", n);
        } catch (Exception e) {
            log.warn("Guild inbound job failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${guild.deadline-interval-ms:3600000}", initialDelayString = "${guild.deadline-initial-delay-ms:90000}")
    public void expireOverdue() {
        try {
            int n = guild.expireOverdue();
            if (n > 0) log.warn("Guild deadlines: {} alert(s) expired, loss assumed", n);
        } catch (Exception e) {
            log.warn("Guild deadline job failed: {}", e.getMessage());
        }
    }
}
