package bank.cardissuing.card.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one card is allowed to do, on top of what its product allows.
 *
 * <p>Channels default to on. Limits default to null, meaning "use the product's";
 * a value here wins over the product for that window. A card with no row at all
 * behaves exactly like a card with a default row, so callers use
 * {@code CardControlsService.forCard} and never need to know which it is.
 */
@Entity
@Table(name = "card_controls")
@Getter
@Setter
@NoArgsConstructor
public class CardControls extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, unique = true)
    private Card card;

    private boolean posEnabled = true;
    private boolean atmEnabled = true;
    private boolean ecommerceEnabled = true;
    private boolean contactlessEnabled = true;
    private boolean internationalEnabled = true;

    /** While set and not past, international is allowed even if switched off. */
    private LocalDate travelNoticeUntil;

    @Column(precision = 19, scale = 2)
    private BigDecimal dailyLimit;
    @Column(precision = 19, scale = 2)
    private BigDecimal weeklyLimit;
    @Column(precision = 19, scale = 2)
    private BigDecimal monthlyLimit;
    @Column(precision = 19, scale = 2)
    private BigDecimal perTransactionMax;

    public CardControls(Card card) {
        this.card = card;
    }

    public boolean allows(Channel channel) {
        return switch (channel) {
            case POS -> posEnabled;
            case ATM -> atmEnabled;
            case ECOMMERCE -> ecommerceEnabled;
            case CONTACTLESS -> contactlessEnabled;
        };
    }

    public boolean allowsInternational(LocalDate today) {
        if (internationalEnabled) return true;
        return travelNoticeUntil != null && !travelNoticeUntil.isBefore(today);
    }
}
