package bank.cardissuing.card.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.card.exception.InvalidContractStateException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Contrato de emisión de tarjetas: vincula a la empresa o persona (Customer,
 * natural o jurídica) que solicita lanzar un programa de tarjetas con las
 * tarjetas (CardProduct) que se crean bajo ese acuerdo. Un CardProduct solo
 * puede publicarse (y por lo tanto emitirse a clientes) si está asociado a
 * un contrato en estado ACTIVE.
 */
@Table(name = "card_issuance_contracts")
@Entity
@Getter
@Setter
@NoArgsConstructor
public class CardIssuanceContract extends BaseEntity {

    @Column(name = "contract_number", nullable = false, unique = true)
    private String contractNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status = ContractStatus.DRAFT;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(length = 1000)
    private String terms;

    public void activate() {
        if (status != ContractStatus.DRAFT && status != ContractStatus.SUSPENDED) {
            throw new InvalidContractStateException("Cannot activate contract from status: " + status);
        }
        this.status = ContractStatus.ACTIVE;
        this.signedAt = LocalDateTime.now();
    }

    public void suspend() {
        if (status != ContractStatus.ACTIVE) {
            throw new InvalidContractStateException("Cannot suspend contract from status: " + status);
        }
        this.status = ContractStatus.SUSPENDED;
    }

    public void terminate() {
        if (status == ContractStatus.TERMINATED) {
            throw new InvalidContractStateException("Contract is already terminated");
        }
        this.status = ContractStatus.TERMINATED;
    }
}
