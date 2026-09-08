package bank.cardissuing.fraud.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The alternative to a decline: ask the cardholder to prove it is them. Created on a
 * 1A, verified with the code, then consumed by the retried authorization -- once.
 */
@Entity
@Table(name = "step_up_challenges", indexes = @Index(name = "ix_challenge_token", columnList = "token", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class StepUpChallenge extends BaseEntity {

    public enum Status { PENDING, VERIFIED, CONSUMED, EXPIRED, FAILED }

    @Column(nullable = false, length = 80)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 50)
    private String merchantId;

    @Column(length = 20)
    private String channel;

    /** The one-time code. Delivery (SMS, push) is outside this module. */
    @Column(nullable = false, length = 12)
    private String otp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    private LocalDateTime expiresAt;

    private int attempts;

    public StepUpChallenge(String token, Card card, BigDecimal amount, String merchantId, String channel, String otp, LocalDateTime expiresAt) {
        this.token = token;
        this.card = card;
        this.amount = amount;
        this.merchantId = merchantId;
        this.channel = channel;
        this.otp = otp;
        this.expiresAt = expiresAt;
    }

    public boolean expired(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
