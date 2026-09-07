package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.Promotion;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionRepository promotionRepository;
    private final CardProductRepository cardProductRepository;

    @GetMapping
    public ResponseEntity<List<Promotion>> getAllPromotions() {
        return ResponseEntity.ok(promotionRepository.findAll());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Promotion> createPromotion(@RequestBody PromotionCreateRequest request) {
        log.info("Creating new Promotion: {}", request.getName());

        CardProduct product = null;
        if (request.getTargetProductId() != null) {
            product = cardProductRepository.findById(request.getTargetProductId()).orElse(null);
        }

        Promotion promo = new Promotion();
        promo.setName(request.getName());
        promo.setDescription(request.getDescription());
        promo.setCashbackPercentage(request.getCashbackPercentage() != null ? request.getCashbackPercentage() : BigDecimal.ZERO);
        promo.setDiscountPercentage(request.getDiscountPercentage() != null ? request.getDiscountPercentage() : BigDecimal.ZERO);
        promo.setPromotionType(request.getPromotionType() != null ? request.getPromotionType() : "CASHBACK");
        promo.setMerchantCategory(request.getMerchantCategory() != null ? request.getMerchantCategory() : "ALL_MERCHANTS");
        promo.setStartDate(request.getStartDate() != null ? request.getStartDate() : "2026-01-01");
        promo.setEndDate(request.getEndDate() != null ? request.getEndDate() : "2026-12-31");
        promo.setMinPurchaseAmount(request.getMinPurchaseAmount() != null ? request.getMinPurchaseAmount() : BigDecimal.ZERO);
        promo.setMaxBenefitCap(request.getMaxBenefitCap() != null ? request.getMaxBenefitCap() : new BigDecimal("100.00"));
        promo.setTargetProduct(product);
        promo.setActive(true);

        promo = promotionRepository.save(promo);
        return ResponseEntity.ok(promo);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Promotion> updatePromotion(@PathVariable Long id, @RequestBody PromotionCreateRequest request) {
        log.info("Updating Promotion ID: {}", id);
        Promotion promo = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found with ID: " + id));

        if (request.getName() != null) promo.setName(request.getName());
        if (request.getDescription() != null) promo.setDescription(request.getDescription());
        if (request.getCashbackPercentage() != null) promo.setCashbackPercentage(request.getCashbackPercentage());
        if (request.getDiscountPercentage() != null) promo.setDiscountPercentage(request.getDiscountPercentage());
        if (request.getPromotionType() != null) promo.setPromotionType(request.getPromotionType());
        if (request.getMerchantCategory() != null) promo.setMerchantCategory(request.getMerchantCategory());
        if (request.getStartDate() != null) promo.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) promo.setEndDate(request.getEndDate());
        if (request.getMinPurchaseAmount() != null) promo.setMinPurchaseAmount(request.getMinPurchaseAmount());
        if (request.getMaxBenefitCap() != null) promo.setMaxBenefitCap(request.getMaxBenefitCap());

        if (request.getTargetProductId() != null) {
            CardProduct product = cardProductRepository.findById(request.getTargetProductId()).orElse(null);
            promo.setTargetProduct(product);
        } else {
            promo.setTargetProduct(null);
        }

        promo = promotionRepository.save(promo);
        return ResponseEntity.ok(promo);
    }

    @PatchMapping("/{id}/toggle")
    @Transactional
    public ResponseEntity<Promotion> togglePromotionStatus(@PathVariable Long id) {
        Promotion promo = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found with ID: " + id));
        promo.setActive(!promo.isActive());
        promo = promotionRepository.save(promo);
        return ResponseEntity.ok(promo);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deletePromotion(@PathVariable Long id) {
        promotionRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/simulate")
    public ResponseEntity<PromotionBenefitCalculation> simulateBenefit(@RequestBody SimulationRequest request) {
        BigDecimal txAmount = request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO;
        String category = request.getMerchantCategory() != null ? request.getMerchantCategory() : "ALL_MERCHANTS";

        List<Promotion> promotions = promotionRepository.findAll().stream()
                .filter(Promotion::isActive)
                .filter(p -> p.getTargetProduct() == null || (request.getProductId() != null && p.getTargetProduct().getId().equals(request.getProductId())))
                .filter(p -> "ALL_MERCHANTS".equalsIgnoreCase(p.getMerchantCategory()) || p.getMerchantCategory().equalsIgnoreCase(category))
                .toList();

        BigDecimal totalCashback = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        String appliedPromoName = "Ninguna promoción aplicable";

        for (Promotion p : promotions) {
            if (txAmount.compareTo(p.getMinPurchaseAmount()) >= 0) {
                if (p.getCashbackPercentage() != null && p.getCashbackPercentage().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal cb = txAmount.multiply(p.getCashbackPercentage()).divide(new BigDecimal("100"));
                    if (p.getMaxBenefitCap() != null && cb.compareTo(p.getMaxBenefitCap()) > 0) {
                        cb = p.getMaxBenefitCap();
                    }
                    totalCashback = totalCashback.add(cb);
                }
                if (p.getDiscountPercentage() != null && p.getDiscountPercentage().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal disc = txAmount.multiply(p.getDiscountPercentage()).divide(new BigDecimal("100"));
                    if (p.getMaxBenefitCap() != null && disc.compareTo(p.getMaxBenefitCap()) > 0) {
                        disc = p.getMaxBenefitCap();
                    }
                    totalDiscount = totalDiscount.add(disc);
                }
                appliedPromoName = p.getName();
            }
        }

        BigDecimal finalAmount = txAmount.subtract(totalDiscount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) finalAmount = BigDecimal.ZERO;

        PromotionBenefitCalculation result = new PromotionBenefitCalculation();
        result.setOriginalAmount(txAmount);
        result.setDiscountApplied(totalDiscount);
        result.setCashbackEarned(totalCashback);
        result.setFinalAmount(finalAmount);
        result.setAppliedPromotionName(appliedPromoName);
        result.setCategory(category);

        return ResponseEntity.ok(result);
    }

    @Data
    public static class PromotionCreateRequest {
        private String name;
        private String description;
        private BigDecimal cashbackPercentage;
        private BigDecimal discountPercentage;
        private String promotionType;
        private String merchantCategory;
        private String startDate;
        private String endDate;
        private BigDecimal minPurchaseAmount;
        private BigDecimal maxBenefitCap;
        private Long targetProductId;
    }

    @Data
    public static class SimulationRequest {
        private Long productId;
        private BigDecimal amount;
        private String merchantCategory;
    }

    @Data
    public static class PromotionBenefitCalculation {
        private BigDecimal originalAmount;
        private BigDecimal discountApplied;
        private BigDecimal cashbackEarned;
        private BigDecimal finalAmount;
        private String appliedPromotionName;
        private String category;
    }
}
