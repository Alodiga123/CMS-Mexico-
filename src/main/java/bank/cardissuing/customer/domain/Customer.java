package bank.cardissuing.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Table(name = "customers")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends BaseEntity {

    private String fullName;
    private String phoneNumber;

    /** Client id in the core banking system (Mifos), once one was created or found for this customer. */
    @Column(name = "external_client_id", length = 80)
    private String externalClientId;

    // ---- identity captured at registration (what the KYC checks against)
    @Column(length = 120) private String firstNames;
    @Column(length = 80) private String paternalSurname;
    @Column(length = 80) private String maternalSurname;
    private LocalDate birthDate;
    /** H (hombre) or M (mujer), as the CURP spells it. */
    @Column(length = 1) private String sex;
    @Column(length = 18) private String curp;
    @Column(length = 13) private String rfc;
    @Column(length = 120) private String email;
    @Column(length = 40) private String nationality;
    @Column(length = 80) private String occupation;
    @Column(length = 160) private String addressLine;
    @Column(length = 10) private String postalCode;
    @Column(length = 40) private String state;
    /** Persona políticamente expuesta, por declaración del titular. */
    @Column(nullable = false, columnDefinition = "boolean not null default false") private boolean pep = false;

    public Customer(String fullName, String phoneNumber) {
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
    }
}
