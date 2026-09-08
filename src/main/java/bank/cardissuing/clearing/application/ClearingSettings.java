package bank.cardissuing.clearing.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Clearing and settlement policy. */
@Component
@ConfigurationProperties(prefix = "clearing")
@Getter
@Setter
public class ClearingSettings {
    /** How far above the authorized amount a presentment may go and still settle (tips, fuel). */
    private BigDecimal tolerancePercent = new BigDecimal("15");
    /** Interchange the simulator assumes the acquirer owes the issuer, as a percentage of each presentment. */
    private BigDecimal simulatedInterchangePercent = new BigDecimal("1.15");
    /** Currency of the settlement files. */
    private String currency = "MXN";
}
