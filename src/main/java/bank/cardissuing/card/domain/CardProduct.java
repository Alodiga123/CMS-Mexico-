package bank.cardissuing.card.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Table(name = "card_products")
@Entity
@Getter
@Setter
@NoArgsConstructor
public class CardProduct extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String productCode;

    @Column(nullable = false)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardType cardType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardNetwork network;

    @Column(nullable = false)
    private String bin;

    @Column(nullable = false)
    private String currency;

    @Column(precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(precision = 19, scale = 2)
    private BigDecimal weeklyLimit;

    @Column(precision = 19, scale = 2)
    private BigDecimal monthlyLimit;

    private String country;

    @Column(name = "card_color", length = 500)
    private String cardColor;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "pin_block_format")
    private String pinBlockFormat = "ISO-0";

    @Column(name = "pvk_index")
    private String pvkIndex = "PVK-01";

    @Column(name = "cvk_index")
    private String cvkIndex = "CVK-A";

    @Column(name = "hsm_algorithm")
    private String hsmAlgorithm = "TDES-2KEY";

    // FEE SCHEDULE & COMMISSIONS (TARIFARIO DE COMISIONES)
    @Column(name = "issuance_fee", precision = 19, scale = 2)
    private BigDecimal issuanceFee = new BigDecimal("10.00");

    @Column(name = "monthly_maintenance_fee", precision = 19, scale = 2)
    private BigDecimal monthlyMaintenanceFee = new BigDecimal("2.50");

    @Column(name = "atm_withdrawal_fee_fixed", precision = 19, scale = 2)
    private BigDecimal atmWithdrawalFeeFixed = new BigDecimal("2.00");

    @Column(name = "atm_withdrawal_fee_percent", precision = 5, scale = 2)
    private BigDecimal atmWithdrawalFeePercent = new BigDecimal("1.50");

    @Column(name = "international_tx_fee_percent", precision = 5, scale = 2)
    private BigDecimal internationalTxFeePercent = new BigDecimal("2.50");

    @Column(name = "replacement_fee", precision = 19, scale = 2)
    private BigDecimal replacementFee = new BigDecimal("15.00");

    @Column(name = "inactivity_fee", precision = 19, scale = 2)
    private BigDecimal inactivityFee = new BigDecimal("5.00");

    @Column(name = "late_payment_fee", precision = 19, scale = 2)
    private BigDecimal latePaymentFee = new BigDecimal("25.00");

    @Column(name = "annual_interest_rate", precision = 5, scale = 2)
    private BigDecimal annualInterestRate = new BigDecimal("18.50");

    @Column(nullable = false)
    private boolean active = true;

    public CardProduct(String productCode, String productName, CardType cardType, PaymentType paymentType, CardNetwork network, String bin, String currency, BigDecimal creditLimit, boolean active) {
        this.productCode = productCode;
        this.productName = productName;
        this.cardType = cardType;
        this.paymentType = paymentType;
        this.network = network;
        this.bin = bin;
        this.currency = currency;
        this.creditLimit = creditLimit;
        this.active = active;
        this.dailyLimit = new BigDecimal("1000");
        this.weeklyLimit = new BigDecimal("5000");
        this.monthlyLimit = new BigDecimal("20000");
        this.country = "USA";
    }

    public CardProduct(String productCode, String productName, CardType cardType, PaymentType paymentType, CardNetwork network, String bin, String currency, BigDecimal creditLimit, BigDecimal dailyLimit, BigDecimal weeklyLimit, BigDecimal monthlyLimit, String country, boolean active) {
        this.productCode = productCode;
        this.productName = productName;
        this.cardType = cardType;
        this.paymentType = paymentType;
        this.network = network;
        this.bin = bin;
        this.currency = currency;
        this.creditLimit = creditLimit;
        this.dailyLimit = dailyLimit;
        this.weeklyLimit = weeklyLimit;
        this.monthlyLimit = monthlyLimit;
        this.country = country;
        this.active = active;
    }
}
