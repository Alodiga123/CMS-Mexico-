package bank.cardissuing.funds.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled for the funds module (hold expiry). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
