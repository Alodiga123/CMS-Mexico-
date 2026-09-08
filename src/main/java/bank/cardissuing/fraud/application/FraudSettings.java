package bank.cardissuing.fraud.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Knobs of the online risk engine, bound from {@code fraud.*}. Thresholds and windows
 * are data so risk can tune them without a release.
 */
@Component
@ConfigurationProperties(prefix = "fraud")
@Getter
@Setter
public class FraudSettings {

    /** Score at which the engine asks for step-up instead of approving. */
    private int reviewThreshold = 50;
    /** Score at which the engine declines outright. */
    private int declineThreshold = 80;

    private Velocity velocity = new Velocity();
    private Declines declines = new Declines();
    private Enumeration enumeration = new Enumeration();
    private Amount amount = new Amount();
    private NewCard newCard = new NewCard();
    private GeoJump geoJump = new GeoJump();
    private StepUp stepUp = new StepUp();

    @Getter @Setter public static class Velocity { private int windowMin = 10; private int max = 5; private int weight = 40; }
    @Getter @Setter public static class Declines { private int windowMin = 30; private int max = 3; private int weight = 50; }
    @Getter @Setter public static class Enumeration { private int windowMin = 10; private int minAttempts = 4; private int minCards = 3; private int weight = 60; }
    @Getter @Setter public static class Amount {
        private int historyDays = 30; private int minHistory = 3; private int anomalyMultiplier = 5; private int anomalyWeight = 30;
        private BigDecimal firstTransactionMax = new BigDecimal("3000"); private int firstTransactionWeight = 20;
    }
    @Getter @Setter public static class NewCard { private int hours = 24; private BigDecimal max = new BigDecimal("1000"); private int weight = 20; }
    @Getter @Setter public static class GeoJump { private int hours = 2; private int weight = 30; }
    @Getter @Setter public static class StepUp { private int ttlMin = 5; private int maxAttempts = 3; private boolean exposeOtp = false; }
}
