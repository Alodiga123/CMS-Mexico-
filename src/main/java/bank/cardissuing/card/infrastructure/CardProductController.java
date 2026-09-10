package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.application.CardProductService;
import bank.cardissuing.card.application.CardProductService.ProductInput;
import bank.cardissuing.card.domain.CardProduct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Card products: list, get by id or code, create (201) and update. */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class CardProductController {

    private final CardProductService service;

    @GetMapping
    public ResponseEntity<List<CardProduct>> getAllProducts() { return ResponseEntity.ok(service.all()); }

    @GetMapping("/by-code/{code}")
    public ResponseEntity<CardProduct> getByCode(@PathVariable String code) { return ResponseEntity.ok(service.byCode(code)); }

    /**
     * BIN ranges this issuer owns, for acquirers and switches that must recognise our cards
     * (on-us routing). Lightweight on purpose: no limits, fees or key indexes. Active products
     * only unless {@code activeOnly=false}.
     */
    @GetMapping("/bins")
    public ResponseEntity<List<BinInfo>> bins(@RequestParam(defaultValue = "true") boolean activeOnly) {
        List<BinInfo> out = service.all().stream()
                .filter(p -> !activeOnly || p.isActive())
                .filter(p -> p.getBin() != null && !p.getBin().isBlank())
                .map(p -> new BinInfo(p.getBin(), p.getNetwork() != null ? p.getNetwork().name() : null,
                        p.getCardType() != null ? p.getCardType().name() : null,
                        p.getPaymentType() != null ? p.getPaymentType().name() : null,
                        p.getProductCode(), p.getProductName(), p.getCurrency(), p.isActive()))
                .toList();
        return ResponseEntity.ok(out);
    }

    public record BinInfo(String bin, String network, String cardType, String paymentType, String productCode,
                          String productName, String currency, boolean active) { }

    @GetMapping("/{id}")
    public ResponseEntity<CardProduct> getProductById(@PathVariable Long id) { return ResponseEntity.ok(service.get(id)); }

    @PostMapping
    public ResponseEntity<CardProduct> createProduct(@RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toInput(), request.getBy()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CardProduct> updateProduct(@PathVariable Long id, @RequestBody ProductCreateRequest request) {
        return ResponseEntity.ok(service.update(id, request.toInput(), request.getBy()));
    }

    @Data
    public static class ProductCreateRequest {
        private String productCode;  // honoured when given; generated (DEB-MX-001) when not
        private String productName;
        private String cardType;     // DEBIT, CREDIT, PREPAID
        private String paymentType;  // PREPAID, POSTPAID
        private String network;      // VISA, MASTERCARD, ALODIGA_PRIVATE
        private String bin;          // 6 to 8 digits
        private String currency;     // USD, MXN, VES, VND
        private BigDecimal creditLimit;
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private String country;
        private String cardColor;
        private String logoUrl;
        private String pinBlockFormat; // ISO-0, ISO-1, ISO-3
        private String pvkIndex;       // PVK-01, PVK-02
        private String cvkIndex;       // CVK-A, CVK-B
        private String hsmAlgorithm;   // TDES-2KEY, AES-128
        private BigDecimal issuanceFee;
        private BigDecimal monthlyMaintenanceFee;
        private BigDecimal atmWithdrawalFeeFixed;
        private BigDecimal atmWithdrawalFeePercent;
        private BigDecimal internationalTxFeePercent;
        private BigDecimal replacementFee;
        private BigDecimal inactivityFee;
        private BigDecimal latePaymentFee;
        private BigDecimal annualInterestRate;
        private Boolean active;
        private String by;             // who is asking, for the audit trail

        ProductInput toInput() {
            return new ProductInput(productCode, productName, cardType, paymentType, network, bin, currency, creditLimit,
                    dailyLimit, weeklyLimit, monthlyLimit, country, cardColor, logoUrl, pinBlockFormat, pvkIndex, cvkIndex,
                    hsmAlgorithm, issuanceFee, monthlyMaintenanceFee, atmWithdrawalFeeFixed, atmWithdrawalFeePercent,
                    internationalTxFeePercent, replacementFee, inactivityFee, latePaymentFee, annualInterestRate, active);
        }
    }
}
