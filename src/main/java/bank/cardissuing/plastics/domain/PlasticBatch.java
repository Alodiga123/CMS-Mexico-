package bank.cardissuing.plastics.domain;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * One personalization file sent to the manufacturer. The file is kept encrypted
 * under the bureau's key; the SHA-256 of the plaintext travels with it so both
 * sides can prove what was sent.
 */
@Entity
@Table(name = "plastic_batches", indexes = @Index(name = "ix_batch_number", columnList = "batch_number", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class PlasticBatch extends BaseEntity {

    public enum Status { BUILT, SENT, PRODUCED }

    @Column(name = "batch_number", nullable = false, length = 32)
    private String batchNumber;

    @Column(nullable = false, length = 80)
    private String manufacturer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.BUILT;

    private int recordCount;
    private int failedCount;

    @Column(length = 64)
    private String sha256Plain;

    @Column(length = 32)
    private String algorithm = "AES-256-GCM";

    @Column(length = 32)
    private String iv;

    @Column(columnDefinition = "bytea")
    private byte[] encryptedFile;

    @Column(length = 80)
    private String builtBy;

    private LocalDateTime sentAt;
    private LocalDateTime producedAt;

    public PlasticBatch(String batchNumber, String manufacturer, String builtBy) {
        this.batchNumber = batchNumber;
        this.manufacturer = manufacturer;
        this.builtBy = builtBy;
    }

    public void send() {
        if (status != Status.BUILT) throw conflict("send");
        this.status = Status.SENT;
        this.sentAt = LocalDateTime.now();
    }

    public void produced(int failed) {
        if (status != Status.SENT) throw conflict("mark produced");
        this.status = Status.PRODUCED;
        this.failedCount = failed;
        this.producedAt = LocalDateTime.now();
    }

    private BusinessException conflict(String action) {
        return new BusinessException("BATCH_INVALID_STATE",
                "Cannot " + action + " a batch in status " + status, HttpStatus.CONFLICT);
    }
}
