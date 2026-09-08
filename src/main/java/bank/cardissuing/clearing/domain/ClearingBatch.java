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
 * One clearing file received from a network for a settlement cycle: what it said it
 * carried (trailer), what we found in it, and what we did with every record. The file
 * itself is sealed so a dispute about a posting can go back to the exact bytes.
 */
@Entity
@Table(name = "clearing_batches", indexes = {
        @Index(name = "ix_clearing_batch_cycle", columnList = "network, cycle_date")
})
@Getter
@Setter
@NoArgsConstructor
public class ClearingBatch extends BaseEntity {

    public enum Status { LOADED, PROCESSED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Network network;

    @Column(name = "file_name", nullable = false, length = 160)
    private String fileName;

    @Column(name = "file_id", length = 60)
    private String fileId;

    @Column(name = "cycle_date", nullable = false)
    private LocalDate cycleDate;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.LOADED;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "trailer_count")
    private Integer trailerCount;

    @Column(name = "trailer_amount", precision = 19, scale = 2)
    private BigDecimal trailerAmount;

    @Column(name = "presentments_count", nullable = false)
    private int presentmentsCount;

    @Column(name = "presentments_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal presentmentsAmount = BigDecimal.ZERO;

    @Column(name = "reversals_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal reversalsAmount = BigDecimal.ZERO;

    @Column(name = "chargebacks_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal chargebacksAmount = BigDecimal.ZERO;

    @Column(name = "fees_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal feesAmount = BigDecimal.ZERO;

    /** Interchange the acquirers owe us on the presentments (the issuer receives it). */
    @Column(name = "interchange_amount", precision = 19, scale = 2)
    private BigDecimal interchangeAmount = BigDecimal.ZERO;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "exception_count", nullable = false)
    private int exceptionCount;

    @Column(name = "loaded_by", length = 80)
    private String loadedBy;

    private LocalDateTime processedAt;

    @Column(name = "settlement_cycle_id")
    private Long settlementCycleId;

    @Column(columnDefinition = "text")
    private String content;

    @Column(length = 500)
    private String error;
}
