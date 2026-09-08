package bank.cardissuing.standin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in: what the CMS may approve on its own while the core is unreachable. Only the
 * product families whose funds live in the core need it (prepaid and credit are local).
 */
@Component
@ConfigurationProperties(prefix = "standin")
@Getter
@Setter
public class StandInSettings {
    private boolean enabled = true;
    /** Largest single operation approved without the core. */
    private BigDecimal maxAmount = new BigDecimal("2000");
    /** Largest total approved without the core per card in a rolling 24 hours. */
    private BigDecimal maxDailyPerCard = new BigDecimal("5000");
    /** Most operations approved without the core per card in a rolling 24 hours. */
    private int maxCountPerCardDaily = 5;
    /** Channels allowed in stand-in (ATM cash is usually kept out). */
    private List<String> channels = new ArrayList<>(List.of("POS", "ECOMMERCE", "CONTACTLESS"));
    /** Seconds between attempts to place stand-in reservations in the core once it is back. */
    private long settleIntervalMs = 60_000;
}
