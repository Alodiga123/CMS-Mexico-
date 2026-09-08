package bank.cardissuing.thirdparty.registry;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * The contract behind each third party the CMS depends on: who the vendor is, where the
 * paperwork stands, when it expires, who to call, and the certification checklist that
 * has to be green before the connection is switched from simulated to real.
 */
@Entity
@Table(name = "third_party_contracts", uniqueConstraints = @UniqueConstraint(name = "ux_third_party_key", columnNames = "provider_key"))
@Getter
@Setter
@NoArgsConstructor
public class ThirdPartyContract extends BaseEntity {

    public enum Status { PENDING, NEGOTIATION, SIGNED, CERTIFYING, ACTIVE, SUSPENDED, TERMINATED }

    @Column(name = "provider_key", nullable = false, length = 40)
    private String providerKey;

    @Column(length = 160)
    private String vendor;

    @Column(name = "contract_ref", length = 80)
    private String contractRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "signed_at")
    private LocalDate signedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(length = 200)
    private String contact;

    @Column(name = "sla_notes", columnDefinition = "text")
    private String slaNotes;

    /** One item per line, "[x]" done, "[ ]" pending. */
    @Column(columnDefinition = "text")
    private String checklist;

    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    public int checklistDone() { return count("[x]"); }

    public int checklistTotal() { return checklist == null ? 0 : (int) checklist.lines().filter(l -> !l.isBlank()).count(); }

    private int count(String mark) {
        return checklist == null ? 0 : (int) checklist.lines().filter(l -> l.trim().toLowerCase().startsWith(mark)).count();
    }
}
