package bank.cardissuing.disputes.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A document attached to a dispute, sealed: its SHA-256 is computed when it comes in
 * and travels with it into the file, so anyone can prove later that what was sent to
 * the acquirer is what the customer handed over.
 */
@Entity
@Table(name = "dispute_evidences", indexes = @Index(name = "ix_evidence_dispute", columnList = "dispute_id"))
@Getter
@Setter
@NoArgsConstructor
public class DisputeEvidence extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispute_id", nullable = false)
    private Dispute dispute;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(length = 120)
    private String contentType;

    private long size;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(length = 500)
    private String description;

    @Column(length = 80)
    private String addedBy;

    /** The bytes themselves. Kept here so the file is self-contained; a store can replace this later. */
    @Column(columnDefinition = "bytea")
    private byte[] content;

    public DisputeEvidence(Dispute dispute, String filename, String contentType, byte[] content,
                           String sha256, String description, String addedBy) {
        this.dispute = dispute;
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
        this.size = content != null ? content.length : 0;
        this.sha256 = sha256;
        this.description = description;
        this.addedBy = addedBy;
    }
}
