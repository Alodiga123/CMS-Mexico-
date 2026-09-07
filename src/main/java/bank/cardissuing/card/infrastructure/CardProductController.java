package bank.cardissuing.card.infrastructure;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.CardIssuanceContract;
import bank.cardissuing.card.domain.CardNetwork;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class CardProductController {

    private final CardProductRepository cardProductRepository;
    private final CardIssuanceContractRepository contractRepository;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<CardProduct>> getAllProducts() {
        return ResponseEntity.ok(cardProductRepository.findAll());
    }

    @GetMapping("/published")
    public ResponseEntity<List<CardProduct>> getPublishedProducts() {
        List<CardProduct> published = cardProductRepository.findAll().stream()
                .filter(CardProduct::isPublished)
                .collect(Collectors.toList());
        return ResponseEntity.ok(published);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CardProduct> getProductById(@PathVariable Long id) {
        return cardProductRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CardProduct> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        log.info("Creating new Card Product: {}", request.getProductName());

        CardProduct product = new CardProduct();
        product.setProductCode("PROD-" + System.currentTimeMillis() % 100000);
        product.setProductName(request.getProductName());
        product.setCardType(CardType.valueOf(request.getCardType().toUpperCase()));
        product.setPaymentType(PaymentType.valueOf(request.getPaymentType().toUpperCase()));
        product.setNetwork(CardNetwork.valueOf(request.getNetwork().toUpperCase()));
        product.setBin(request.getBin());
        product.setCurrency(request.getCurrency() != null ? request.getCurrency() : "USD");
        product.setCreditLimit(request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO);
        product.setDailyLimit(request.getDailyLimit() != null ? request.getDailyLimit() : new BigDecimal("1000"));
        product.setWeeklyLimit(request.getWeeklyLimit() != null ? request.getWeeklyLimit() : new BigDecimal("5000"));
        product.setMonthlyLimit(request.getMonthlyLimit() != null ? request.getMonthlyLimit() : new BigDecimal("20000"));
        product.setCountry(request.getCountry() != null && !request.getCountry().isBlank() ? request.getCountry() : "USA");
        product.setCardColor(request.getCardColor() != null && !request.getCardColor().isBlank() ? request.getCardColor() : "linear-gradient(135deg, #0f172a, #1e1b4b, #312e81)");
        product.setLogoUrl(request.getLogoUrl() != null && !request.getLogoUrl().isBlank() ? request.getLogoUrl() : "DEFAULT");
        product.setPinBlockFormat(request.getPinBlockFormat() != null && !request.getPinBlockFormat().isBlank() ? request.getPinBlockFormat() : "ISO-0");
        product.setPvkIndex(request.getPvkIndex() != null && !request.getPvkIndex().isBlank() ? request.getPvkIndex() : "PVK-01");
        product.setCvkIndex(request.getCvkIndex() != null && !request.getCvkIndex().isBlank() ? request.getCvkIndex() : "CVK-A");
        product.setHsmAlgorithm(request.getHsmAlgorithm() != null && !request.getHsmAlgorithm().isBlank() ? request.getHsmAlgorithm() : "TDES-2KEY");
        if (request.getIssuanceFee() != null) product.setIssuanceFee(request.getIssuanceFee());
        if (request.getMonthlyMaintenanceFee() != null) product.setMonthlyMaintenanceFee(request.getMonthlyMaintenanceFee());
        if (request.getAtmWithdrawalFeeFixed() != null) product.setAtmWithdrawalFeeFixed(request.getAtmWithdrawalFeeFixed());
        if (request.getAtmWithdrawalFeePercent() != null) product.setAtmWithdrawalFeePercent(request.getAtmWithdrawalFeePercent());
        if (request.getInternationalTxFeePercent() != null) product.setInternationalTxFeePercent(request.getInternationalTxFeePercent());
        if (request.getReplacementFee() != null) product.setReplacementFee(request.getReplacementFee());
        if (request.getInactivityFee() != null) product.setInactivityFee(request.getInactivityFee());
        if (request.getLatePaymentFee() != null) product.setLatePaymentFee(request.getLatePaymentFee());
        if (request.getAnnualInterestRate() != null) product.setAnnualInterestRate(request.getAnnualInterestRate());
        product.setActive(true);

        if (request.getContractId() != null) {
            CardIssuanceContract contract = contractRepository.findById(request.getContractId())
                    .orElseThrow(() -> new ResourceNotFoundException("CardIssuanceContract", "id", request.getContractId()));
            product.setContract(contract);
        }

        product = cardProductRepository.save(product);
        return ResponseEntity.ok(product);
    }

    @PostMapping("/{id}/publish")
    @Transactional
    public ResponseEntity<CardProduct> publishProduct(@PathVariable Long id) {
        CardProduct product = cardProductRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CardProduct", "id", id));
        product.publish();
        product = cardProductRepository.save(product);
        auditService.log("PUBLISH_PRODUCT", "CardProduct", product.getId().toString(), "SYSTEM");
        log.info("Card product published: id={}, contractId={}", product.getId(), product.getContractId());
        return ResponseEntity.ok(product);
    }

    @PostMapping("/{id}/unpublish")
    @Transactional
    public ResponseEntity<CardProduct> unpublishProduct(@PathVariable Long id) {
        CardProduct product = cardProductRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CardProduct", "id", id));
        product.unpublish();
        product = cardProductRepository.save(product);
        auditService.log("UNPUBLISH_PRODUCT", "CardProduct", product.getId().toString(), "SYSTEM");
        return ResponseEntity.ok(product);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CardProduct> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductCreateRequest request) {
        log.info("Updating Card Product ID: {}", id);
        CardProduct product = cardProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with ID: " + id));

        if (request.getProductName() != null) product.setProductName(request.getProductName());
        if (request.getCardType() != null) product.setCardType(CardType.valueOf(request.getCardType().toUpperCase()));
        if (request.getPaymentType() != null) product.setPaymentType(PaymentType.valueOf(request.getPaymentType().toUpperCase()));
        if (request.getNetwork() != null) product.setNetwork(CardNetwork.valueOf(request.getNetwork().toUpperCase()));
        if (request.getBin() != null) product.setBin(request.getBin());
        if (request.getCurrency() != null) product.setCurrency(request.getCurrency());
        if (request.getCountry() != null) product.setCountry(request.getCountry());
        if (request.getDailyLimit() != null) product.setDailyLimit(request.getDailyLimit());
        if (request.getWeeklyLimit() != null) product.setWeeklyLimit(request.getWeeklyLimit());
        if (request.getMonthlyLimit() != null) product.setMonthlyLimit(request.getMonthlyLimit());
        if (request.getCreditLimit() != null) product.setCreditLimit(request.getCreditLimit());
        if (request.getCardColor() != null) product.setCardColor(request.getCardColor());
        if (request.getLogoUrl() != null) product.setLogoUrl(request.getLogoUrl());
        if (request.getPinBlockFormat() != null) product.setPinBlockFormat(request.getPinBlockFormat());
        if (request.getPvkIndex() != null) product.setPvkIndex(request.getPvkIndex());
        if (request.getCvkIndex() != null) product.setCvkIndex(request.getCvkIndex());
        if (request.getHsmAlgorithm() != null) product.setHsmAlgorithm(request.getHsmAlgorithm());
        if (request.getIssuanceFee() != null) product.setIssuanceFee(request.getIssuanceFee());
        if (request.getMonthlyMaintenanceFee() != null) product.setMonthlyMaintenanceFee(request.getMonthlyMaintenanceFee());
        if (request.getAtmWithdrawalFeeFixed() != null) product.setAtmWithdrawalFeeFixed(request.getAtmWithdrawalFeeFixed());
        if (request.getAtmWithdrawalFeePercent() != null) product.setAtmWithdrawalFeePercent(request.getAtmWithdrawalFeePercent());
        if (request.getInternationalTxFeePercent() != null) product.setInternationalTxFeePercent(request.getInternationalTxFeePercent());
        if (request.getReplacementFee() != null) product.setReplacementFee(request.getReplacementFee());
        if (request.getInactivityFee() != null) product.setInactivityFee(request.getInactivityFee());
        if (request.getLatePaymentFee() != null) product.setLatePaymentFee(request.getLatePaymentFee());
        if (request.getAnnualInterestRate() != null) product.setAnnualInterestRate(request.getAnnualInterestRate());
        if (request.getContractId() != null) {
            CardIssuanceContract contract = contractRepository.findById(request.getContractId())
                    .orElseThrow(() -> new ResourceNotFoundException("CardIssuanceContract", "id", request.getContractId()));
            product.setContract(contract);
        }

        product = cardProductRepository.save(product);
        return ResponseEntity.ok(product);
    }

    @Data
    public static class ProductCreateRequest {
        private String productName;
        private String cardType;     // DEBIT, CREDIT, PREPAID
        private String paymentType;  // PREPAID, POSTPAID
        private String network;      // VISA, MASTERCARD, ALODIGA_PRIVATE
        private String bin;          // e.g. 453211
        private String currency;     // USD, VES, VND
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
        private Long contractId;
    }
}
