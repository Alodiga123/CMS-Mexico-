package bank.cardissuing.customer.domain;

import bank.cardissuing.customer.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** The cardholder's KYC file: identification document, outcome of the checks and, when an analyst decided, why. */
@Table(name = "kyc")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KYC extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    private KYCStatus status;

    private String documentType;
    private String documentNumber;
    private LocalDateTime verifiedAt;
    /** Validity of the identification document. */
    private LocalDateTime expiresAt;

    /** LOW, MEDIUM, HIGH after the last run. */
    @Column(length = 10) private String riskLevel;
    /** The checks of the last run, as JSON [{code, ok, detail}]. */
    @Column(columnDefinition = "text") private String checksJson;
    private LocalDateTime screenedAt;
    @Column(length = 500) private String reviewNote;
    @Column(length = 80) private String reviewedBy;
    private LocalDateTime reviewedAt;

    public void verify() {
        if (this.status != KYCStatus.PENDING && this.status != KYCStatus.REVIEW) {
            throw new InvalidStateTransitionException("Cannot verify KYC from status: " + this.status);
        }
        this.status = KYCStatus.VERIFIED;
        this.verifiedAt = LocalDateTime.now();
    }

    public void reject() {
        if (this.status != KYCStatus.PENDING && this.status != KYCStatus.REVIEW) {
            throw new InvalidStateTransitionException("Cannot reject KYC from status: " + this.status);
        }
        this.status = KYCStatus.REJECTED;
    }
}
