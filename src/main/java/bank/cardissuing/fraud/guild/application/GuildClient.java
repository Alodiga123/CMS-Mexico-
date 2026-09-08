package bank.cardissuing.fraud.guild.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The port to the industry antifraud systems. Three services behind one door:
 * SVL (online verification of compromised cards and merchants), SNA (alerts both ways),
 * SPC (early notice of a dispute to prevent the chargeback).
 */
public interface GuildClient {

    /** Answer of the verification list. */
    record Hit(boolean listed, String folio, String reason, LocalDateTime listedAt) {
        public static Hit clean() { return new Hit(false, null, null, null); }
    }

    /** What we send. */
    record Outbound(String localRef, String type, String bin, String last4, String merchantId, String merchantName,
                    String description, BigDecimal amount, String externalRef) { }

    /** What arrives from the guild. */
    record Inbound(String folio, String type, String bin, String last4, String merchantId, String merchantName,
                   String description, LocalDateTime issuedAt, String source) { }

    record Health(boolean up, String detail) { }

    String mode();

    Health health();

    Hit verifyCard(String bin, String last4);

    Hit verifyMerchant(String merchantId);

    /** Sends and returns the guild's folio. Throws on any failure (the outbox retries). */
    String send(Outbound message);

    List<Inbound> fetchInbound(LocalDateTime since);
}
