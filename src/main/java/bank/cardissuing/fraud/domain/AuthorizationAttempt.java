package bank.cardissuing.fraud.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Every authorization decision, approved or not. Holds only remember approvals; the
 * fraud rules need the declines too -- card testing is made of declines -- and the
 * console needs the full history of what a card tried to do.
 */
@Entity
@Table(name = "authorization_attempts", indexes = {
        @Index(name = "ix_attempt_card_at", columnList = "card_id,created_at"),
        @Index(name = "ix_attempt_merchant_at", columnList = "merchant_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuthorizationAttempt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(length = 40)
    private String approvalCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 100)
    private String merchantName;

    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    @Column(length = 20)
    private String channel;

    @Column(length = 3)
    private String countryCode;

    @Column(nullable = false, length = 4)
    private String responseCode;

    private boolean approved;

    private int riskScore;

    /** Comma-separated rule codes that fired. */
    @Column(length = 500)
    private String riskReasons;

    private boolean stepUp;

    public AuthorizationAttempt(Card card, BigDecimal amount, String merchantName, String merchantId,
                                String channel, String countryCode, String responseCode, boolean approved,
                                String approvalCode, int riskScore, String riskReasons, boolean stepUp) {
        this.card = card;
        this.amount = amount;
        this.merchantName = merchantName;
        this.merchantId = merchantId;
        this.channel = channel;
        this.countryCode = countryCode;
        this.responseCode = responseCode;
        this.approved = approved;
        this.approvalCode = approvalCode;
        this.riskScore = riskScore;
        this.riskReasons = riskReasons;
        this.stepUp = stepUp;
    }
}
