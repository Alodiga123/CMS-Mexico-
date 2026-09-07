package bank.cardissuing.customer.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table(name = "customers")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false)
    private CustomerType customerType = CustomerType.PERSONA_NATURAL;

    private String fullName;

    private String phoneNumber;

    private String email;

    private String address;

    // RFC (persona física u homoclave de persona moral, según customerType)
    @Column(name = "tax_id")
    private String taxId;

    // --- Campos exclusivos de Persona Jurídica ---
    @Column(name = "business_name")
    private String businessName; // Razón social

    @Column(name = "legal_representative_name")
    private String legalRepresentativeName;

    @Column(name = "legal_representative_tax_id")
    private String legalRepresentativeTaxId;

    public boolean isJuridica() {
        return customerType == CustomerType.PERSONA_JURIDICA;
    }

    public String getDisplayName() {
        return isJuridica() && businessName != null && !businessName.isBlank() ? businessName : fullName;
    }

}
