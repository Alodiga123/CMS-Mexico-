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

    private String fullName;

    private String phoneNumber;

    /** Client id in the core banking system (Mifos), once one was created or found for this customer. */
    @Column(name = "external_client_id", length = 80)
    private String externalClientId;

    public Customer(String fullName, String phoneNumber) {
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
    }

}
