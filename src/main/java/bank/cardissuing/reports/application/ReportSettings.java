package bank.cardissuing.reports.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Thresholds behind the reports; every one of them is a property. */
@Component
@ConfigurationProperties(prefix = "reports")
@Getter
@Setter
public class ReportSettings {
    /** PLD: a single approved operation at or above this amount is "relevante". */
    private BigDecimal relevantThreshold = new BigDecimal("10000");
    /** PLD: a card whose approved operations in the period add up to this is reported as accumulated. */
    private BigDecimal accumulatedThreshold = new BigDecimal("25000");
    /** PLD: an approved attempt scored at or above this counts as "inusual" even without an alert. */
    private int unusualRiskScore = 40;
    /** Inactivity report: days without an approved operation. */
    private int inactiveDays = 90;
    /** Cap on rows returned per report, so a runaway period cannot flood the console. */
    private int maxRows = 5000;
    /** Default period length in days when only 'to' (or nothing) is given. */
    private int defaultPeriodDays = 30;
}
