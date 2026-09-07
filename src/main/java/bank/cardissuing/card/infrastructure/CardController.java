package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardCategory;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardRepository cardRepository;
    private final CardProductRepository cardProductRepository;
    private final CustomerRepository customerRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final bank.cardissuing.hsm.infrastructure.HsmService hsmService;

    @GetMapping
    public ResponseEntity<List<CardResponse>> getAllCards(@RequestParam(required = false) Long customerId) {
        List<Card> cards = cardRepository.findAll();
        if (customerId != null) {
            cards = cards.stream()
                    .filter(c -> c.getCustomer() != null && customerId.equals(c.getCustomer().getId()))
                    .collect(Collectors.toList());
        }
        List<CardResponse> responses = cards.stream().map(card -> {
            Customer customer = card.getCustomer();
            CardProduct product = card.getProduct();
            BigDecimal balance = BigDecimal.ZERO;
            LedgerAccount account = ledgerAccountRepository.findByCard(card).orElse(null);
            if (account != null) {
                balance = ledgerEntryRepository.calculateBalance(account);
            }
            return new CardResponse(
                    card.getId(),
                    customer != null ? customer.getId() : null,
                    customer != null ? customer.getDisplayName() : "N/A",
                    customer != null ? customer.getCustomerType().name() : "N/A",
                    card.getEmbossedName() != null ? card.getEmbossedName() : (customer != null ? customer.getDisplayName().toUpperCase() : "CARDHOLDER"),
                    product != null ? product.getProductName() : "Standard Card",
                    product != null ? product.getCardType().name() : "DEBIT",
                    product != null ? product.getPaymentType().name() : "PREPAID",
                    product != null ? product.getNetwork().name() : "VISA",
                    card.getLast4(),
                    card.getCardCategory() != null ? card.getCardCategory().name() : "PHYSICAL",
                    card.getStatus().name(),
                    card.getExpiryDate() != null ? card.getExpiryDate().toString() : "N/A",
                    balance,
                    product != null ? product.getCurrency() : "USD",
                    product != null && product.getCountry() != null ? product.getCountry() : "USA",
                    card.getEffectiveDailyLimit() != null ? card.getEffectiveDailyLimit() : new BigDecimal("1000"),
                    card.getEffectiveWeeklyLimit() != null ? card.getEffectiveWeeklyLimit() : new BigDecimal("5000"),
                    card.getEffectiveMonthlyLimit() != null ? card.getEffectiveMonthlyLimit() : new BigDecimal("20000"),
                    card.isPinSet(),
                    card.isOnlinePurchasesEnabled(),
                    card.isInternationalPurchasesEnabled(),
                    card.isContactlessEnabled(),
                    card.isAtmWithdrawalsEnabled()
            );
        }).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @PostMapping("/issue")
    @Transactional
    public ResponseEntity<CardResponse> issueCustomCard(@RequestBody CardIssueRequest request) {
        log.info("Issuing custom card for customer ID: {}, product ID: {}", request.getCustomerId(), request.getProductId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found with ID: " + request.getCustomerId()));

        CardProduct product = null;
        if (request.getProductId() != null) {
            product = cardProductRepository.findById(request.getProductId()).orElse(null);
            if (product != null && !product.isPublished()) {
                throw new bank.cardissuing.card.exception.ProductNotPublishedException(product.getId());
            }
        }

        String last4 = (request.getLast4() != null && request.getLast4().length() == 4)
                ? request.getLast4()
                : String.format("%04d", new Random().nextInt(10000));

        // Invoke Simulador-HSM-Alodiga on http://localhost:8080 to generate cryptograms
        String bin = product != null ? product.getBin() : "453211";
        String format = product != null ? product.getPinBlockFormat() : "ISO-0";
        String pvk = product != null ? product.getPvkIndex() : "PVK-01";
        bank.cardissuing.hsm.infrastructure.HsmService.HsmCardCryptoResult crypto = hsmService.generateCardCryptograms(bin, last4, format, pvk);

        log.info("HSM Cryptogram Result from PayShield Simulator (http://localhost:8080): PINBlock={}, PVV={}, CVV2={}, Status={}",
                crypto.getPinBlock(), crypto.getPvv(), crypto.getCvv2(), crypto.getStatus());

        Card card = new Card();
        card.setCustomer(customer);
        card.setProduct(product);
        card.setEmbossedName(request.getEmbossedName() != null && !request.getEmbossedName().isBlank()
                ? request.getEmbossedName().toUpperCase()
                : customer.getDisplayName().toUpperCase());
        card.setLast4(last4);
        card.setStatus(CardStatus.ACTIVE);
        card.setCardCategory(request.getCardCategory() != null ? CardCategory.valueOf(request.getCardCategory().toUpperCase()) : CardCategory.PHYSICAL);
        card.setExpiryDate(LocalDate.now().plusYears(3));
        card = cardRepository.save(card);

        // Ledger Account Creation
        LedgerAccount account = new LedgerAccount();
        account.setCard(card);
        account.setCurrency(product != null ? product.getCurrency() : "USD");
        account = ledgerAccountRepository.save(account);

        BigDecimal initialDeposit = request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO;
        if (initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            LedgerEntry entry = new LedgerEntry(account, EntryType.CREDIT, initialDeposit, "Initial Issuance Deposit (HSM Encrypted PIN Block: " + crypto.getPinBlock() + ")");
            ledgerEntryRepository.save(entry);
        }

        BigDecimal balance = ledgerEntryRepository.calculateBalance(account);

        CardResponse response = new CardResponse(
                card.getId(),
                customer.getId(),
                customer.getDisplayName(),
                customer.getCustomerType().name(),
                card.getEmbossedName(),
                product != null ? product.getProductName() : "Standard Card",
                product != null ? product.getCardType().name() : "DEBIT",
                product != null ? product.getPaymentType().name() : "PREPAID",
                product != null ? product.getNetwork().name() : "VISA",
                card.getLast4(),
                card.getCardCategory().name(),
                card.getStatus().name(),
                card.getExpiryDate().toString(),
                balance,
                product != null ? product.getCurrency() : "USD",
                product != null && product.getCountry() != null ? product.getCountry() : "USA",
                card.getEffectiveDailyLimit() != null ? card.getEffectiveDailyLimit() : new BigDecimal("1000"),
                card.getEffectiveWeeklyLimit() != null ? card.getEffectiveWeeklyLimit() : new BigDecimal("5000"),
                card.getEffectiveMonthlyLimit() != null ? card.getEffectiveMonthlyLimit() : new BigDecimal("20000"),
                card.isPinSet(),
                card.isOnlinePurchasesEnabled(),
                card.isInternationalPurchasesEnabled(),
                card.isContactlessEnabled(),
                card.isAtmWithdrawalsEnabled()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/recharge")
    @Transactional
    public ResponseEntity<?> rechargeCard(@PathVariable Long id, @RequestBody RechargeRequest request) {
        log.info("Recharging card ID {} with amount {}", id, request.getAmount());
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Card not found with ID: " + id));

        LedgerAccount account = ledgerAccountRepository.findByCard(card)
                .orElseThrow(() -> new RuntimeException("Ledger Account not found for Card ID: " + id));

        LedgerEntry entry = new LedgerEntry(
                account,
                EntryType.CREDIT,
                request.getAmount(),
                request.getNote() != null ? request.getNote() : "Card Recharge / Top-up"
        );
        ledgerEntryRepository.save(entry);

        BigDecimal newBalance = ledgerEntryRepository.calculateBalance(account);

        return ResponseEntity.ok(Map.of(
                "cardId", card.getId(),
                "embossedName", card.getEmbossedName() != null ? card.getEmbossedName() : "CARDHOLDER",
                "rechargeAmount", request.getAmount(),
                "newBalance", newBalance,
                "message", "Card recharged successfully!"
        ));
    }

    @PostMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateCardStatus(@PathVariable Long id, @RequestBody StatusUpdateRequest request) {
        log.info("Updating card ID {} status to {}", id, request.getStatus());
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Card not found with ID: " + id));

        CardStatus newStatus = CardStatus.valueOf(request.getStatus().toUpperCase());
        card.setStatus(newStatus);
        cardRepository.save(card);

        return ResponseEntity.ok(Map.of(
                "cardId", card.getId(),
                "newStatus", card.getStatus().name(),
                "message", "Card status updated successfully"
        ));
    }

    @Data
    public static class CardIssueRequest {
        private Long customerId;
        private Long productId;
        private String embossedName;
        private String cardCategory; // PHYSICAL, VIRTUAL
        private String last4;
        private BigDecimal initialDeposit;
    }

    @Data
    public static class RechargeRequest {
        private BigDecimal amount;
        private String note;
    }

    @Data
    public static class StatusUpdateRequest {
        private String status;
    }

    @Data
    public static class CardResponse {
        private final Long id;
        private final Long customerId;
        private final String customerName;
        private final String customerType;
        private final String embossedName;
        private final String productName;
        private final String cardType;
        private final String paymentType;
        private final String network;
        private final String last4;
        private final String cardCategory;
        private final String status;
        private final String expiryDate;
        private final BigDecimal balance;
        private final String currency;
        private final String country;
        private final BigDecimal dailyLimit;
        private final BigDecimal weeklyLimit;
        private final BigDecimal monthlyLimit;
        private final boolean pinSet;
        private final boolean onlinePurchasesEnabled;
        private final boolean internationalPurchasesEnabled;
        private final boolean contactlessEnabled;
        private final boolean atmWithdrawalsEnabled;
    }
}
