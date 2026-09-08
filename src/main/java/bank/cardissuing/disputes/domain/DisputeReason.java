package bank.cardissuing.disputes.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A reason code with the clocks it starts. Days are calendar days: the chargeback
 * window counts from the transaction, the representment window from the chargeback,
 * the resolution window from the day the case was opened.
 */
@Entity
@Table(name = "dispute_reasons")
@Getter
@Setter
@NoArgsConstructor
public class DisputeReason extends BaseEntity {

    @Column(nullable = false, unique = true, length = 12)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    /** VISA, MASTERCARD or ANY (domestic scheme). */
    @Column(nullable = false, length = 12)
    private String network;

    private int chargebackDays;
    private int representmentDays;
    private int resolveDays;

    /** The scheme expects documents from the cardholder for this reason. */
    private boolean requiresEvidence;

    private boolean active = true;

    public DisputeReason(String code, String description, String network,
                         int chargebackDays, int representmentDays, int resolveDays, boolean requiresEvidence) {
        this.code = code;
        this.description = description;
        this.network = network;
        this.chargebackDays = chargebackDays;
        this.representmentDays = representmentDays;
        this.resolveDays = resolveDays;
        this.requiresEvidence = requiresEvidence;
    }
}
