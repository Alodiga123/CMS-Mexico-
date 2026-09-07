package bank.cardissuing.funds.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.BaseEntity;
import bank.cardissuing.common.exception.BusinessException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A reservation of funds created by an approved authorization.
 *
 * <p>This is the shadow balance of the issuer: what has been approved but not yet
 * cleared. Available funds are always computed as (balance - sum of HELD holds), so the
 * ledger stays append-only and the authorization never writes a debit by itself.
 *
 * <p>For core-backed debit cards the hold also exists in the core (see
 * {@code externalRef}); for prepaid and credit it lives only here.
 */
@Entity
@Table(name = "authorization_holds", indexes = {
        @Index(name = "ix_hold_card_status", columnList = "card_id,status"),
        @Index(name = "ix_hold_card_created", columnList = "card_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuthorizationHold extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false, unique = true, length = 40)
    private String approvalCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(precision = 19, scale = 2)
    private BigDecimal capturedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HoldStatus status = HoldStatus.HELD;

    @Column(length = 100)
    private String merchantName;

    @Column(length = 50)
    private String merchantId;

    @Column(length = 30)
    private String transactionType;

    /** Reference of the hold in the core, when the funds live there. */
    @Column(length = 80)
    private String externalRef;

    @Column(length = 120)
    private String idempotencyKey;

    private LocalDateTime expiresAt;

    public AuthorizationHold(Card card, String approvalCode, BigDecimal amount,
                             String merchantName, String merchantId, String transactionType,
                             LocalDateTime expiresAt) {
        this.card = card;
        this.approvalCode = approvalCode;
        this.amount = amount;
        this.merchantName = merchantName;
        this.merchantId = merchantId;
        this.transactionType = transactionType;
        this.expiresAt = expiresAt;
    }

    public void capture(BigDecimal captured) {
        requireHeld("capture");
        if (captured.compareTo(BigDecimal.ZERO) <= 0 || captured.compareTo(amount) > 0) {
            throw new BusinessException("INVALID_CAPTURE_AMOUNT",
                    "Capture amount must be between 0 and the held amount " + amount,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        this.capturedAmount = captured;
        this.status = HoldStatus.CAPTURED;
    }

    public void release() {
        requireHeld("release");
        this.status = HoldStatus.RELEASED;
    }

    public void expire() {
        requireHeld("expire");
        this.status = HoldStatus.EXPIRED;
    }

    private void requireHeld(String action) {
        if (this.status != HoldStatus.HELD) {
            throw new BusinessException("HOLD_INVALID_STATE",
                    "Cannot " + action + " a hold in status " + this.status,
                    HttpStatus.CONFLICT);
        }
    }
}
