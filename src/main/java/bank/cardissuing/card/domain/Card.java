package bank.cardissuing.card.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import bank.cardissuing.customer.domain.Customer;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Table(name = "cards")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Card extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id")
    private CardProduct product;

    private String embossedName;

    private String last4;

    @Enumerated(EnumType.STRING)
    private CardStatus status;

    @Enumerated(EnumType.STRING)
    private CardCategory cardCategory = CardCategory.PHYSICAL;

    private LocalDate expiryDate;

    @Column(precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /**
     * Account id in the core banking system (for Mifos, the savings account id).
     * Only meaningful for products whose funds live in the core; null otherwise.
     */
    @Column(name = "external_account_id", length = 80)
    private String externalAccountId;

    public Card(Customer customer, String last4, CardStatus status, LocalDate expiryDate) {
        this.customer = customer;
        this.last4 = last4;
        this.status = status;
        this.expiryDate = expiryDate;
    }

    public void activate() {
        if (this.status != CardStatus.CREATED) {
            throw new IllegalStateException("Cannot activate card from status: " + this.status);
        }
        this.status = CardStatus.ACTIVE;
    }

    public void suspend() {
        if (this.status != CardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot suspend card from status: " + this.status);
        }
        this.status = CardStatus.SUSPENDED;
    }

    public void block() {
        if (this.status != CardStatus.ACTIVE && this.status != CardStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot block card from status: " + this.status);
        }
        this.status = CardStatus.BLOCKED;
    }

    public void close() {
        if (this.status == CardStatus.BLOCKED || this.status == CardStatus.SUSPENDED) {
            this.status = CardStatus.CLOSED;
        } else {
            throw new IllegalStateException("Cannot close card from status: " + this.status);
        }
    }

    public void validateForAuthorization() {
        if (this.status != CardStatus.ACTIVE) {
            throw new IllegalStateException("Card is not active. Current status: " + this.status);
        }
        if (this.expiryDate != null && this.expiryDate.isBefore(LocalDate.now())) {
            throw new IllegalStateException("Card is expired.");
        }
    }
}
