package bank.cardissuing.card.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.CardNetwork;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.card.infrastructure.CardProductRepository;
import bank.cardissuing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Card products: the code is the product's public identity (it is what scripts,
 * the console and the reports refer to), so it is honoured when given, generated
 * readably when not, and never duplicated. Bad input is a 400, a clash a 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardProductService {

    /** Upper-case letters, digits, dash and underscore; 2 to 30 characters, starting with a letter or digit. */
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,29}$");
    static final Pattern BIN = Pattern.compile("^\\d{6,8}$");

    private final CardProductRepository products;
    private final AuditService audit;

    /** Everything a product can be created or updated with. Null means "not given". */
    public record ProductInput(String productCode, String productName, String cardType, String paymentType, String network,
                               String bin, String currency, BigDecimal creditLimit, BigDecimal dailyLimit, BigDecimal weeklyLimit,
                               BigDecimal monthlyLimit, String country, String cardColor, String logoUrl, String pinBlockFormat,
                               String pvkIndex, String cvkIndex, String hsmAlgorithm, BigDecimal issuanceFee,
                               BigDecimal monthlyMaintenanceFee, BigDecimal atmWithdrawalFeeFixed, BigDecimal atmWithdrawalFeePercent,
                               BigDecimal internationalTxFeePercent, BigDecimal replacementFee, BigDecimal inactivityFee,
                               BigDecimal latePaymentFee, BigDecimal annualInterestRate, Boolean active) { }

    public List<CardProduct> all() { return products.findAll(); }

    public CardProduct get(Long id) {
        return products.findById(id).orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public CardProduct byCode(String code) {
        String c = normalizeCode(code);
        return products.findByProductCode(c).orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product '" + c + "' not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public CardProduct create(ProductInput in, String by) {
        if (in.productName() == null || in.productName().isBlank()) throw bad("productName is required");
        if (in.cardType() == null || in.paymentType() == null || in.network() == null) throw bad("cardType, paymentType and network are required");
        CardType cardType = parse(CardType.class, in.cardType(), "cardType");
        PaymentType paymentType = parse(PaymentType.class, in.paymentType(), "paymentType");
        CardNetwork network = parse(CardNetwork.class, in.network(), "network");
        String country = in.country() != null && !in.country().isBlank() ? in.country().trim().toUpperCase(Locale.ROOT) : "USA";
        String code = in.productCode() != null && !in.productCode().isBlank() ? requireFree(normalizeCode(in.productCode()), null)
                : generateCode(cardType, country);

        CardProduct p = new CardProduct();
        p.setProductCode(code);
        p.setProductName(in.productName().trim());
        p.setCardType(cardType);
        p.setPaymentType(paymentType);
        p.setNetwork(network);
        p.setBin(requireBin(in.bin()));
        p.setCurrency(in.currency() != null && !in.currency().isBlank() ? in.currency().trim().toUpperCase(Locale.ROOT) : "USD");
        p.setCreditLimit(nonNegative(in.creditLimit(), "creditLimit", BigDecimal.ZERO));
        p.setDailyLimit(nonNegative(in.dailyLimit(), "dailyLimit", new BigDecimal("1000")));
        p.setWeeklyLimit(nonNegative(in.weeklyLimit(), "weeklyLimit", new BigDecimal("5000")));
        p.setMonthlyLimit(nonNegative(in.monthlyLimit(), "monthlyLimit", new BigDecimal("20000")));
        p.setCountry(country);
        p.setCardColor(orDefault(in.cardColor(), "linear-gradient(135deg, #0f172a, #1e1b4b, #312e81)"));
        p.setLogoUrl(orDefault(in.logoUrl(), "DEFAULT"));
        p.setPinBlockFormat(orDefault(in.pinBlockFormat(), "ISO-0"));
        p.setPvkIndex(orDefault(in.pvkIndex(), "PVK-01"));
        p.setCvkIndex(orDefault(in.cvkIndex(), "CVK-A"));
        p.setHsmAlgorithm(orDefault(in.hsmAlgorithm(), "TDES-2KEY"));
        applyFees(p, in);
        p.setActive(in.active() == null || in.active());
        p = products.save(p);
        audit.log("CREATE_PRODUCT", "CardProduct", p.getProductCode(), by != null ? by : "API");
        log.info("Product {} '{}' created ({} {} {})", p.getProductCode(), p.getProductName(), cardType, paymentType, network);
        return p;
    }

    @Transactional
    public CardProduct update(Long id, ProductInput in, String by) {
        CardProduct p = get(id);
        if (in.productCode() != null && !in.productCode().isBlank()) {
            String code = normalizeCode(in.productCode());
            if (!code.equals(p.getProductCode())) p.setProductCode(requireFree(code, id));
        }
        if (in.productName() != null) {
            if (in.productName().isBlank()) throw bad("productName cannot be blank");
            p.setProductName(in.productName().trim());
        }
        if (in.cardType() != null) p.setCardType(parse(CardType.class, in.cardType(), "cardType"));
        if (in.paymentType() != null) p.setPaymentType(parse(PaymentType.class, in.paymentType(), "paymentType"));
        if (in.network() != null) p.setNetwork(parse(CardNetwork.class, in.network(), "network"));
        if (in.bin() != null) p.setBin(requireBin(in.bin()));
        if (in.currency() != null && !in.currency().isBlank()) p.setCurrency(in.currency().trim().toUpperCase(Locale.ROOT));
        if (in.country() != null && !in.country().isBlank()) p.setCountry(in.country().trim().toUpperCase(Locale.ROOT));
        if (in.dailyLimit() != null) p.setDailyLimit(nonNegative(in.dailyLimit(), "dailyLimit", null));
        if (in.weeklyLimit() != null) p.setWeeklyLimit(nonNegative(in.weeklyLimit(), "weeklyLimit", null));
        if (in.monthlyLimit() != null) p.setMonthlyLimit(nonNegative(in.monthlyLimit(), "monthlyLimit", null));
        if (in.creditLimit() != null) p.setCreditLimit(nonNegative(in.creditLimit(), "creditLimit", null));
        if (in.cardColor() != null) p.setCardColor(in.cardColor());
        if (in.logoUrl() != null) p.setLogoUrl(in.logoUrl());
        if (in.pinBlockFormat() != null) p.setPinBlockFormat(in.pinBlockFormat());
        if (in.pvkIndex() != null) p.setPvkIndex(in.pvkIndex());
        if (in.cvkIndex() != null) p.setCvkIndex(in.cvkIndex());
        if (in.hsmAlgorithm() != null) p.setHsmAlgorithm(in.hsmAlgorithm());
        applyFees(p, in);
        if (in.active() != null) p.setActive(in.active());
        p = products.save(p);
        audit.log("UPDATE_PRODUCT", "CardProduct", p.getProductCode(), by != null ? by : "API");
        return p;
    }

    // ---------------------------------------------------------------- helpers

    /** DEB-MX-001, CRE-USA-002... readable, and unique among what exists. */
    String generateCode(CardType type, String country) {
        String cc = country.replaceAll("[^A-Z]", "");
        cc = cc.isEmpty() ? "XX" : cc.substring(0, Math.min(3, cc.length()));
        final String prefix = type.name().substring(0, 3) + "-" + cc;
        long n = products.findAll().stream().filter(p -> p.getProductCode() != null && p.getProductCode().startsWith(prefix + "-")).count();
        for (int i = 0; i < 1000; i++) {
            String candidate = String.format("%s-%03d", prefix, n + 1 + i);
            if (products.findByProductCode(candidate).isEmpty()) return candidate;
        }
        throw new BusinessException("PRODUCT_CODE_EXHAUSTED", "Could not generate a free product code for " + prefix, HttpStatus.CONFLICT);
    }

    static String normalizeCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace(' ', '-');
        if (!CODE.matcher(c).matches()) {
            throw new BusinessException("PRODUCT_CODE_INVALID",
                    "productCode '" + code + "' must be 2-30 characters: letters, digits, '-' or '_'", HttpStatus.BAD_REQUEST);
        }
        return c;
    }

    private String requireFree(String code, Long exceptId) {
        products.findByProductCode(code).filter(p -> exceptId == null || !p.getId().equals(exceptId)).ifPresent(p -> {
            throw new BusinessException("PRODUCT_CODE_IN_USE", "productCode '" + code + "' already belongs to product " + p.getId() + " (" + p.getProductName() + ")", HttpStatus.CONFLICT);
        });
        return code;
    }

    private static String requireBin(String bin) {
        String b = bin == null ? "" : bin.trim();
        if (!BIN.matcher(b).matches()) throw bad("bin must be 6 to 8 digits");
        return b;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad(field + " '" + value + "' is not one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static BigDecimal nonNegative(BigDecimal v, String field, BigDecimal dflt) {
        if (v == null) return dflt;
        if (v.signum() < 0) throw bad(field + " cannot be negative");
        return v;
    }

    private static String orDefault(String v, String dflt) { return v != null && !v.isBlank() ? v : dflt; }

    private static void applyFees(CardProduct p, ProductInput in) {
        if (in.issuanceFee() != null) p.setIssuanceFee(nonNegative(in.issuanceFee(), "issuanceFee", null));
        if (in.monthlyMaintenanceFee() != null) p.setMonthlyMaintenanceFee(nonNegative(in.monthlyMaintenanceFee(), "monthlyMaintenanceFee", null));
        if (in.atmWithdrawalFeeFixed() != null) p.setAtmWithdrawalFeeFixed(nonNegative(in.atmWithdrawalFeeFixed(), "atmWithdrawalFeeFixed", null));
        if (in.atmWithdrawalFeePercent() != null) p.setAtmWithdrawalFeePercent(nonNegative(in.atmWithdrawalFeePercent(), "atmWithdrawalFeePercent", null));
        if (in.internationalTxFeePercent() != null) p.setInternationalTxFeePercent(nonNegative(in.internationalTxFeePercent(), "internationalTxFeePercent", null));
        if (in.replacementFee() != null) p.setReplacementFee(nonNegative(in.replacementFee(), "replacementFee", null));
        if (in.inactivityFee() != null) p.setInactivityFee(nonNegative(in.inactivityFee(), "inactivityFee", null));
        if (in.latePaymentFee() != null) p.setLatePaymentFee(nonNegative(in.latePaymentFee(), "latePaymentFee", null));
        if (in.annualInterestRate() != null) p.setAnnualInterestRate(nonNegative(in.annualInterestRate(), "annualInterestRate", null));
    }

    private static BusinessException bad(String msg) { return new BusinessException("PRODUCT_INVALID", msg, HttpStatus.BAD_REQUEST); }
}
