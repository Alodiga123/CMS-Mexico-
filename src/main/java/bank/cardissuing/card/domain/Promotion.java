package bank.cardissuing.card.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Table(name = "promotions")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Promotion extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(precision = 5, scale = 2)
    private BigDecimal cashbackPercentage;

    @Column(precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(name = "promotion_type")
    private String promotionType = "CASHBACK"; // CASHBACK, DISCOUNT, ZERO_FEE, REWARD_POINTS

    @Column(name = "merchant_category")
    private String merchantCategory = "ALL_MERCHANTS"; // GAS_STATIONS, SUPERMARKETS, RESTAURANTS, ONLINE_SHOPPING, ALL_MERCHANTS

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "end_date")
    private String endDate;

    @Column(name = "min_purchase_amount", precision = 19, scale = 2)
    private BigDecimal minPurchaseAmount = BigDecimal.ZERO;

    @Column(name = "max_benefit_cap", precision = 19, scale = 2)
    private BigDecimal maxBenefitCap = new BigDecimal("100.00");

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_product_id")
    private CardProduct targetProduct;

    public Promotion(String name, String description, BigDecimal cashbackPercentage, BigDecimal discountPercentage, boolean active, CardProduct targetProduct) {
        this.name = name;
        this.description = description;
        this.cashbackPercentage = cashbackPercentage;
        this.discountPercentage = discountPercentage;
        this.active = active;
        this.targetProduct = targetProduct;
    }
}
