package bank.cardissuing.fraud.guild.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One message exchanged with the industry antifraud systems (the "gremio": SNA alerts,
 * SPC chargeback prevention). Outbound ones live in an outbox until the guild
 * acknowledges them with a folio; inbound ones carry a deadline to answer, after which
 * the loss is assumed to be ours.
 */
@Entity
@Table(name = "guild_alerts", indexes = {
        @Index(name = "ix_guild_alerts_status", columnList = "status"),
        @Index(name = "ix_guild_alerts_folio", columnList = "guild_folio"),
        @Index(name = "ix_guild_alerts_card", columnList = "card_id")
})
@Getter
@Setter
@NoArgsConstructor
public class GuildAlert extends BaseEntity {

    public enum Direction { OUTBOUND, INBOUND }

    public enum Type { ENUMERATION, CONFIRMED_FRAUD, COMPROMISED_CARD, SUSPICIOUS_MERCHANT, CHARGEBACK_PREVENTION, OTHER }

    public enum Status {
        PENDING_SEND, SENT, FAILED,          // outbound
        RECEIVED, IN_REVIEW,                 // inbound
        CLOSED, EXPIRED;                     // both

        public boolean open() { return this != CLOSED && this != EXPIRED; }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Status status;

    @Column(name = "card_id")
    private Long cardId;

    @Column(length = 8)
    private String bin;

    @Column(length = 4)
    private String last4;

    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    @Column(name = "merchant_name", length = 120)
    private String merchantName;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 1000)
    private String description;

    /** Reference the guild gave us (outbound: on acknowledgement; inbound: theirs). */
    @Column(name = "guild_folio", length = 60)
    private String guildFolio;

    /** Where this came from on our side: FRAUD_ALERT:12, DISPUTE:5, MANUAL, INBOUND. */
    @Column(name = "source_ref", length = 60)
    private String sourceRef;

    @Column(name = "raised_by", length = 80)
    private String raisedBy;

    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    private LocalDateTime sentAt;
    private LocalDateTime receivedAt;

    /** Date by which we must answer / close (SNA deadline); after it, {@link #assumedLoss}. */
    private LocalDate respondBy;

    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 80)
    private String closedBy;

    @Column(length = 500)
    private String resolution;

    /** What the CMS did on its own when the message arrived (CARD_BLOCKED, MERCHANT_BLOCKLISTED...). */
    @Column(name = "auto_action", length = 60)
    private String autoAction;

    /** Deadline passed without closing: per the guild's rules the loss is assumed to be the issuer's. */
    private boolean assumedLoss;

    public static GuildAlert outbound(Type type, String sourceRef, String by) {
        GuildAlert a = new GuildAlert();
        a.direction = Direction.OUTBOUND;
        a.type = type;
        a.status = Status.PENDING_SEND;
        a.sourceRef = sourceRef;
        a.raisedBy = by;
        return a;
    }

    public static GuildAlert inbound(Type type, String folio, LocalDateTime receivedAt) {
        GuildAlert a = new GuildAlert();
        a.direction = Direction.INBOUND;
        a.type = type;
        a.status = Status.RECEIVED;
        a.guildFolio = folio;
        a.sourceRef = "INBOUND";
        a.receivedAt = receivedAt;
        return a;
    }

    public void sent(String folio, LocalDateTime when) {
        this.status = Status.SENT;
        this.guildFolio = folio;
        this.sentAt = when;
        this.lastError = null;
        this.attempts++;
    }

    public void failed(String error) {
        this.status = Status.FAILED;
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        this.attempts++;
    }

    public void close(String resolution, String by, LocalDateTime when) {
        this.status = Status.CLOSED;
        this.resolution = resolution;
        this.closedBy = by;
        this.closedAt = when;
    }

    public void expire(LocalDateTime when) {
        this.status = Status.EXPIRED;
        this.assumedLoss = true;
        this.closedAt = when;
        this.resolution = "Deadline " + respondBy + " passed without an answer: loss assumed per guild rules";
    }
}
