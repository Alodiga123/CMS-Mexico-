package bank.cardissuing.funds.reconciliation;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One thing the CMS and the core disagree about, for one core account.
 *
 * <p>Opened by a reconciliation run, closed automatically by a later run that no longer
 * sees the difference, or by hand with a note. The key makes re-runs idempotent: the
 * same difference is one open item, not one per run.
 */
@Entity
@Table(name = "reconciliation_items", indexes = {
        @Index(name = "ix_recon_account_status", columnList = "account_id,status"),
        @Index(name = "ix_recon_key", columnList = "item_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationItem extends BaseEntity {

    public enum Type {
        /** CMS still holds; the core released it or never had it. */
        HOLD_MISSING_IN_CORE,
        /** CMS captured; the core has no matching debit. */
        CAPTURE_MISSING_IN_CORE,
        /** The core's debit is not the amount the CMS captured. */
        AMOUNT_MISMATCH,
        /** CMS settled or released, but the core still reserves the money. */
        HOLD_NOT_RELEASED,
        /** A debit in the core that no CMS capture explains. */
        UNKNOWN_CORE_DEBIT,
        /** Sum of CMS holds differs from what the core reports as on hold. */
        HOLD_TOTAL_MISMATCH,
        /** Approved in stand-in while the core was down; when it came back it refused the reservation. */
        STAND_IN_REJECTED,
        /** A clearing presentment without a live authorization was posted anyway. */
        CLEARING_NO_AUTHORIZATION,
        /** The network presented an amount outside the tolerance of the authorization. */
        CLEARING_AMOUNT_MISMATCH,
        /** The same presentment arrived twice. */
        CLEARING_DUPLICATE,
        /** A clearing record for a PAN the CMS does not know. */
        CLEARING_NO_CARD,
        /** A chargeback, representment or reversal that matches nothing on our side. */
        CLEARING_UNMATCHED
    }

    public enum Status { OPEN, RESOLVED }

    @Column(name = "item_key", nullable = false, length = 200)
    private String itemKey;

    @Column(name = "account_id", nullable = false, length = 80)
    private String accountId;

    private Long cardId;

    @Column(length = 40)
    private String approvalCode;

    @Column(length = 80)
    private String coreRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(precision = 19, scale = 2)
    private BigDecimal cmsAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal coreAmount;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;

    @Column(length = 300)
    private String resolution;

    public ReconciliationItem(String itemKey, String accountId, Long cardId, String approvalCode, String coreRef,
                              Type type, BigDecimal cmsAmount, BigDecimal coreAmount, String detail) {
        this.itemKey = itemKey;
        this.accountId = accountId;
        this.cardId = cardId;
        this.approvalCode = approvalCode;
        this.coreRef = coreRef;
        this.type = type;
        this.cmsAmount = cmsAmount;
        this.coreAmount = coreAmount;
        this.detail = detail;
        this.lastSeenAt = LocalDateTime.now();
    }

    public void seenAgain(BigDecimal cmsAmount, BigDecimal coreAmount, String detail) {
        this.cmsAmount = cmsAmount;
        this.coreAmount = coreAmount;
        this.detail = detail;
        this.lastSeenAt = LocalDateTime.now();
    }

    public void resolve(String resolution) {
        this.status = Status.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
        this.resolution = resolution;
    }
}
