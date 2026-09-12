package bank.cardissuing.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An image (or PDF) of the cardholder's identification, kept encrypted with the vault key, and
 * the result of checking that the document belongs to the customer: read by a provider and
 * compared field by field, or confirmed by an analyst looking at it next to the captured data.
 */
@Table(name = "kyc_documents")
@Entity
@Getter
@Setter
@NoArgsConstructor
public class KycDocument extends BaseEntity {

    public enum Side { FRONT, BACK }

    /** Outcome of the match between the document and the customer's data. */
    public enum Verification {
        /** Waiting for an analyst to compare it (manual mode, or provider could not read it). */
        PENDING_MANUAL,
        /** The provider could not read the document. */
        UNREADABLE,
        MATCH,
        MISMATCH
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING) @Column(length = 8, nullable = false) private Side side = Side.FRONT;
    @Column(length = 40) private String documentType;
    @Column(length = 160) private String fileName;
    @Column(length = 80) private String contentType;
    private long sizeBytes;
    @Column(length = 64) private String sha256;
    /** The file, base64 then encrypted with the PAN vault key. */
    @Column(columnDefinition = "text") private String contentEncrypted;
    private LocalDateTime uploadedAt;
    @Column(length = 80) private String uploadedBy;

    @Enumerated(EnumType.STRING) @Column(length = 16) private Verification verification = Verification.PENDING_MANUAL;
    @Column(length = 500) private String verificationDetail;
    /** Fields the provider read from the document, as JSON, for the analyst to see. */
    @Column(columnDefinition = "text") private String extractedJson;
    @Column(length = 80) private String verifiedBy;
    private LocalDateTime verifiedAt;
}
