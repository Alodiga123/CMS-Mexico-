package bank.cardissuing.clearing.domain;

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
 * The issuer's settlement position with one network for one cycle date: everything the
 * network presented against our cards, minus what came back (reversals, chargebacks we
 * won) and the interchange the acquirers owe us. Positive net = we pay the network.
 * Closing seals a settlement file for treasury; paying records the transfer reference.
 */
@Entity
@Table(name = "settlement_cycles", indexes = {
        @Index(name = "ix_settlement_cycle", columnList = "network, cycle_date", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class SettlementCycle extends BaseEntity {

    public enum Status { OPEN, CLOSED, PAID }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Network network;

    @Column(name = "cycle_date", nullable = false)
    private LocalDate cycleDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(name = "batch_count", nullable = false)
    private int batchCount;

    @Column(name = "presentments_count", nullable = false)
    private int presentmentsCount;

    @Column(name = "presentments_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal presentmentsAmount = BigDecimal.ZERO;

    @Column(name = "reversals_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal reversalsAmount = BigDecimal.ZERO;

    @Column(name = "chargebacks_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal chargebacksAmount = BigDecimal.ZERO;

    @Column(name = "interchange_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal interchangeAmount = BigDecimal.ZERO;

    /** presentments - reversals - chargebacks - interchange. Positive: the issuer pays. */
    @Column(name = "net_position", precision = 19, scale = 2, nullable = false)
    private BigDecimal netPosition = BigDecimal.ZERO;

    @Column(name = "exception_count", nullable = false)
    private int exceptionCount;

    @Column(length = 3)
    private String currency = "MXN";

    @Column(columnDefinition = "text")
    private String settlementFile;

    @Column(length = 64)
    private String sha256;

    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 80)
    private String closedBy;

    private LocalDateTime paidAt;

    @Column(name = "payment_ref", length = 80)
    private String paymentRef;

    public void add(ClearingBatch b) {
        batchCount++;
        presentmentsCount += b.getPresentmentsCount();
        presentmentsAmount = presentmentsAmount.add(b.getPresentmentsAmount());
        reversalsAmount = reversalsAmount.add(b.getReversalsAmount());
        chargebacksAmount = chargebacksAmount.add(b.getChargebacksAmount());
        interchangeAmount = interchangeAmount.add(b.getFeesAmount());
        exceptionCount += b.getExceptionCount();
        netPosition = presentmentsAmount.subtract(reversalsAmount).subtract(chargebacksAmount).subtract(interchangeAmount);
    }
}
