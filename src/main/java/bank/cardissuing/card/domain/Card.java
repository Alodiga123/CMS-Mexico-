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
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

    // ---- Administrador de Tarjetas: límites transaccionales por tarjeta/cliente ----
    // Si es null, se hereda el valor por defecto definido en el CardProduct.
    @Column(name = "per_transaction_limit", precision = 19, scale = 2)
    private BigDecimal perTransactionLimit;

    @Column(name = "daily_limit_override", precision = 19, scale = 2)
    private BigDecimal dailyLimitOverride;

    @Column(name = "weekly_limit_override", precision = 19, scale = 2)
    private BigDecimal weeklyLimitOverride;

    @Column(name = "monthly_limit_override", precision = 19, scale = 2)
    private BigDecimal monthlyLimitOverride;

    @Column(name = "atm_daily_limit", precision = 19, scale = 2)
    private BigDecimal atmDailyLimit;

    // ---- Administrador de Tarjetas: controles por canal ----
    @Column(name = "online_purchases_enabled", nullable = false)
    private boolean onlinePurchasesEnabled = true;

    @Column(name = "international_purchases_enabled", nullable = false)
    private boolean internationalPurchasesEnabled = false;

    @Column(name = "contactless_enabled", nullable = false)
    private boolean contactlessEnabled = true;

    @Column(name = "atm_withdrawals_enabled", nullable = false)
    private boolean atmWithdrawalsEnabled = true;

    // ---- Configuración de PIN (bloque generado vía HSM) ----
    @Column(name = "pin_set", nullable = false)
    private boolean pinSet = false;

    @Column(name = "pin_block")
    private String pinBlock;

    @Column(name = "pin_kcv")
    private String pinKcv;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts = 0;

    @Column(name = "pin_set_at")
    private LocalDateTime pinSetAt;

    // ---- Promociones asignadas específicamente a este cliente/tarjeta ----
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "card_promotions",
            joinColumns = @JoinColumn(name = "card_id"),
            inverseJoinColumns = @JoinColumn(name = "promotion_id"))
    private Set<Promotion> assignedPromotions = new HashSet<>();

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

    // ---- Límites transaccionales efectivos (override de tarjeta > default de producto) ----

    public BigDecimal getEffectiveDailyLimit() {
        if (dailyLimitOverride != null) return dailyLimitOverride;
        return product != null ? product.getDailyLimit() : null;
    }

    public BigDecimal getEffectiveWeeklyLimit() {
        if (weeklyLimitOverride != null) return weeklyLimitOverride;
        return product != null ? product.getWeeklyLimit() : null;
    }

    public BigDecimal getEffectiveMonthlyLimit() {
        if (monthlyLimitOverride != null) return monthlyLimitOverride;
        return product != null ? product.getMonthlyLimit() : null;
    }

    // ---- Gestión de PIN ----

    public void assignPin(String pinBlock, String pinKcv, String pinHash) {
        this.pinBlock = pinBlock;
        this.pinKcv = pinKcv;
        this.pinHash = pinHash;
        this.pinSet = true;
        this.failedPinAttempts = 0;
        this.pinSetAt = LocalDateTime.now();
    }

    public void registerFailedPinAttempt() {
        this.failedPinAttempts++;
        if (this.failedPinAttempts >= 3
                && (this.status == CardStatus.ACTIVE || this.status == CardStatus.SUSPENDED)) {
            this.status = CardStatus.BLOCKED;
        }
    }

    public void resetPinAttempts() {
        this.failedPinAttempts = 0;
    }

    // ---- Promociones asignadas al cliente/tarjeta ----

    public void assignPromotion(Promotion promotion) {
        this.assignedPromotions.add(promotion);
    }

    public void removePromotion(Promotion promotion) {
        this.assignedPromotions.remove(promotion);
    }
}
