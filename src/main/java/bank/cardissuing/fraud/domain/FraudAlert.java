package bank.cardissuing.fraud.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Something the engine wants an analyst to look at. */
@Entity
@Table(name = "fraud_alerts", indexes = {
        @Index(name = "ix_alert_status", columnList = "status"),
        @Index(name = "ix_alert_merchant_at", columnList = "merchant_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class FraudAlert extends BaseEntity {

    public enum Type { DECLINED, STEP_UP, ENUMERATION }
    public enum Status { OPEN, REVIEWED, DISMISSED }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private Card card;

    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    @Column(length = 100)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    private int riskScore;

    @Column(length = 500)
    private String reasons;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(length = 500)
    private String analystNote;

    @Column(length = 40)
    private String actionTaken;

    @Column(length = 80)
    private String reviewedBy;

    private LocalDateTime reviewedAt;

    public FraudAlert(Card card, String merchantId, String merchantName, Type type, int riskScore, String reasons, BigDecimal amount) {
        this.card = card;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.type = type;
        this.riskScore = riskScore;
        this.reasons = reasons;
        this.amount = amount;
    }

    public void review(Status outcome, String action, String note, String by) {
        this.status = outcome;
        this.actionTaken = action;
        this.analystNote = note;
        this.reviewedBy = by;
        this.reviewedAt = LocalDateTime.now();
    }
}
