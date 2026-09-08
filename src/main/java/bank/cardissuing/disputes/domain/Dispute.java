package bank.cardissuing.disputes.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.BaseEntity;
import bank.cardissuing.funds.domain.AuthorizationHold;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A cardholder's claim against a settled authorization, seen from the issuer.
 *
 * <p>Life cycle: OPENED (the customer complained) → CHARGEBACK_SENT (we raised it to
 * the acquirer) → REPRESENTED (the acquirer answered with evidence) → resolved for the
 * customer or for the merchant. It can also be WITHDRAWN before an answer. Each step
 * has a deadline that comes from the reason code.
 */
@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "ix_dispute_card", columnList = "card_id"),
        @Index(name = "ix_dispute_status", columnList = "status"),
        @Index(name = "ix_dispute_approval", columnList = "approval_code")
})
@Getter
@Setter
@NoArgsConstructor
public class Dispute extends BaseEntity {

    public enum Status {
        OPENED, CHARGEBACK_SENT, REPRESENTED, RESOLVED_CUSTOMER, RESOLVED_MERCHANT, WITHDRAWN;
        public boolean closed() { return this == RESOLVED_CUSTOMER || this == RESOLVED_MERCHANT || this == WITHDRAWN; }
    }

    public enum Outcome { CUSTOMER, MERCHANT }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    /** The settled authorization being disputed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private AuthorizationHold hold;

    @Column(name = "approval_code", nullable = false, length = 40)
    private String approvalCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reason_id", nullable = false)
    private DisputeReason reason;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.OPENED;

    /** Money given back to the customer while the case is decided. */
    private boolean provisionalCredit;
    @Column(length = 80)
    private String creditRef;
    @Column(length = 80)
    private String creditReversalRef;

    @Column(length = 80)
    private String openedBy;
    @Column(length = 80)
    private String acquirerCaseRef;

    private LocalDate transactionDate;
    private LocalDate chargebackDeadline;
    private LocalDate representmentDeadline;
    private LocalDate resolveBy;
    private boolean deadlineBreached;

    private LocalDateTime chargebackSentAt;
    private LocalDateTime representedAt;
    private LocalDateTime resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Outcome outcome;

    @Column(length = 500)
    private String resolutionNote;

    public Dispute(Card card, AuthorizationHold hold, BigDecimal amount, DisputeReason reason,
                   String description, boolean provisionalCredit, String openedBy) {
        this.card = card;
        this.hold = hold;
        this.approvalCode = hold.getApprovalCode();
        this.amount = amount;
        this.currency = card.getProduct() != null ? card.getProduct().getCurrency() : null;
        this.reason = reason;
        this.description = description;
        this.provisionalCredit = provisionalCredit;
        this.openedBy = openedBy;
    }

    public void sendChargeback(LocalDate representmentDeadline, String acquirerCaseRef) {
        require(Status.OPENED, "send a chargeback for");
        this.status = Status.CHARGEBACK_SENT;
        this.chargebackSentAt = LocalDateTime.now();
        this.representmentDeadline = representmentDeadline;
        this.acquirerCaseRef = acquirerCaseRef;
    }

    public void represent() {
        require(Status.CHARGEBACK_SENT, "record a representment for");
        this.status = Status.REPRESENTED;
        this.representedAt = LocalDateTime.now();
    }

    public void resolve(Outcome outcome, String note) {
        if (status.closed()) throw conflict("resolve");
        this.status = outcome == Outcome.CUSTOMER ? Status.RESOLVED_CUSTOMER : Status.RESOLVED_MERCHANT;
        this.outcome = outcome;
        this.resolvedAt = LocalDateTime.now();
        this.resolutionNote = note;
    }

    public void withdraw(String note) {
        if (status != Status.OPENED && status != Status.CHARGEBACK_SENT) throw conflict("withdraw");
        this.status = Status.WITHDRAWN;
        this.resolvedAt = LocalDateTime.now();
        this.resolutionNote = note;
    }

    /** The date the current step must be done by; null once closed. */
    public LocalDate nextDeadline() {
        return switch (status) {
            case OPENED -> chargebackDeadline;
            case CHARGEBACK_SENT -> representmentDeadline;
            case REPRESENTED -> resolveBy;
            default -> null;
        };
    }

    private void require(Status expected, String action) {
        if (status != expected) throw conflict(action);
    }

    private BusinessException conflict(String action) {
        return new BusinessException("DISPUTE_INVALID_STATE",
                "Cannot " + action + " a dispute in status " + status, HttpStatus.CONFLICT);
    }
}
