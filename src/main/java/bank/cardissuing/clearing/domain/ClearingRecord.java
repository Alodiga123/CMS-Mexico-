package bank.cardissuing.clearing.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a clearing file and what the CMS did with it. The PAN is kept masked and hashed only. */
@Entity
@Table(name = "clearing_records", indexes = {
        @Index(name = "ix_clearing_rec_batch", columnList = "batch_id"),
        @Index(name = "ix_clearing_rec_rrn", columnList = "rrn"),
        @Index(name = "ix_clearing_rec_outcome", columnList = "outcome")
})
@Getter
@Setter
@NoArgsConstructor
public class ClearingRecord extends BaseEntity {

    /** What the network is telling us. */
    public enum Type { PRESENTMENT, REVERSAL, CHARGEBACK, REPRESENTMENT, FEE }

    /** What happened on our side. */
    public enum Outcome {
        MATCHED_CAPTURED,    // presentment settled against its authorization
        AMOUNT_MISMATCH,     // presented amount outside tolerance: captured what was held, difference to reconcile
        ALREADY_CAPTURED,    // duplicate presentment
        FORCE_POSTED,        // no live authorization (released, expired or none): posted anyway, flagged
        NO_CARD,             // PAN unknown to us
        REVERSED,            // clearing reversal credited back
        DISPUTE_LINKED,      // chargeback / representment matched to an open dispute
        FEE_BOOKED,          // interchange booked
        UNMATCHED,           // nothing on our side to tie it to
        ERROR
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ClearingBatch batch;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(name = "pan_masked", length = 24)
    private String panMasked;

    @Column(name = "card_id")
    private Long cardId;

    @Column(length = 12)
    private String rrn;

    @Column(length = 6)
    private String stan;

    /** The six characters the network carries as the issuer's approval id (ISO field 38). */
    @Column(name = "approval_id", length = 6)
    private String approvalId;

    /** Our full approval code once matched. */
    @Column(name = "approval_code", length = 40)
    private String approvalCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(name = "interchange_fee", precision = 19, scale = 2)
    private BigDecimal interchangeFee;

    @Column(length = 4)
    private String mcc;

    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    @Column(name = "merchant_name", length = 120)
    private String merchantName;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(name = "reason_code", length = 10)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Outcome outcome;

    @Column(length = 400)
    private String detail;
}
