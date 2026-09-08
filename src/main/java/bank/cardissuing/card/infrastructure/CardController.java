package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.application.CoreAccountLinker;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.funds.core.CoreBankingClient;
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
import bank.cardissuing.plastics.application.PlasticService;
import bank.cardissuing.plastics.domain.Plastic;
import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
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
    private final PlasticService plasticService;
    private final AuditService auditService;
    private final CardProductRepository cardProductRepository;
    private final CustomerRepository customerRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final bank.cardissuing.hsm.infrastructure.HsmService hsmService;
    private final CoreAccountLinker coreAccountLinker;
    private final CoreBankingClient coreBankingClient;

    @GetMapping
    public ResponseEntity<List<CardResponse>> getAllCards() {
        List<Card> cards = cardRepository.findAll();
        List<CardResponse> responses = cards.stream().map(this::toResponse).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /** One card, same shape as the list. */
    @GetMapping("/{id}")
    public ResponseEntity<CardResponse> getCard(@PathVariable Long id) {
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CARD_NOT_FOUND", "Card " + id + " not found", HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(toResponse(card));
    }

    private CardResponse toResponse(Card card) {
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
                customer != null ? customer.getFullName() : "N/A",
                card.getEmbossedName() != null ? card.getEmbossedName() : (customer != null ? customer.getFullName().toUpperCase() : "CARDHOLDER"),
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
                product != null && product.getDailyLimit() != null ? product.getDailyLimit() : new BigDecimal("1000"),
                product != null && product.getWeeklyLimit() != null ? product.getWeeklyLimit() : new BigDecimal("5000"),
                product != null && product.getMonthlyLimit() != null ? product.getMonthlyLimit() : new BigDecimal("20000")
        );
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
                : customer.getFullName().toUpperCase());
        card.setLast4(last4);
        card.setStatus(CardStatus.ACTIVE);
        card.setCardCategory(request.getCardCategory() != null ? CardCategory.valueOf(request.getCardCategory().toUpperCase()) : CardCategory.PHYSICAL);
        card.setExpiryDate(LocalDate.now().plusYears(3));
        BigDecimal requestedDeposit = request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO;
        // Debit-with-core products get their account in the core here, before anything is saved locally.
        boolean linkedToCore = coreAccountLinker.link(card, customer, request.getExternalAccountId(), requestedDeposit);
        card = cardRepository.save(card);
        if (card.getCardCategory() == CardCategory.PHYSICAL) {
            plasticService.request(card, Plastic.Reason.NEW, null, true, "ISSUANCE");
        }

        // Ledger Account Creation
        LedgerAccount account = new LedgerAccount();
        account.setCard(card);
        account.setCurrency(product != null ? product.getCurrency() : "USD");
        account = ledgerAccountRepository.save(account);

        BigDecimal initialDeposit = request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO;
        if (!linkedToCore && initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            LedgerEntry entry = new LedgerEntry(account, EntryType.CREDIT, initialDeposit, "Initial Issuance Deposit (HSM Encrypted PIN Block: " + crypto.getPinBlock() + ")");
            ledgerEntryRepository.save(entry);
        }

        BigDecimal balance = linkedToCore
                ? coreBankingClient.availableBalance(card.getExternalAccountId())
                : ledgerEntryRepository.calculateBalance(account);

        CardResponse response = new CardResponse(
                card.getId(),
                customer.getId(),
                customer.getFullName(),
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
                product != null && product.getDailyLimit() != null ? product.getDailyLimit() : new BigDecimal("1000"),
                product != null && product.getWeeklyLimit() != null ? product.getWeeklyLimit() : new BigDecimal("5000"),
                product != null && product.getMonthlyLimit() != null ? product.getMonthlyLimit() : new BigDecimal("20000")
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
        CardStatus previous = card.getStatus();
        card.setStatus(newStatus);
        cardRepository.save(card);
        auditService.log("CARD_STATUS_" + newStatus.name(), "Card", card.getId().toString(),
                request.getBy() != null && !request.getBy().isBlank() ? request.getBy() : "API");
        log.info("Card {} status {} -> {}", card.getId(), previous, newStatus);

        return ResponseEntity.ok(Map.of(
                "cardId", card.getId(),
                "newStatus", card.getStatus().name(),
                "message", "Card status updated successfully"
        ));
    }
    /** Where a card's money lives in the core, if anywhere. */
    @GetMapping("/{id}/core-account")
    public ResponseEntity<java.util.Map<String, Object>> coreAccount(@PathVariable Long id) {
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new bank.cardissuing.common.exception.ResourceNotFoundException("Card", "id", id));
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("cardId", id);
        m.put("coreBacked", coreAccountLinker.isCoreBacked(card.getProduct()));
        m.put("externalAccountId", card.getExternalAccountId());
        m.put("externalClientId", card.getCustomer() != null ? card.getCustomer().getExternalClientId() : null);
        m.put("available", card.getExternalAccountId() != null ? coreBankingClient.availableBalance(card.getExternalAccountId()) : null);
        return ResponseEntity.ok(m);
    }


    @Data
    public static class CardIssueRequest {
        private Long customerId;
        private Long productId;
        private String embossedName;
        private String cardCategory; // PHYSICAL, VIRTUAL
        private String last4;
        private BigDecimal initialDeposit;
        /** Optional: link to this existing core account instead of opening a new one. */
        private String externalAccountId;
    }

    @Data
    public static class RechargeRequest {
        private BigDecimal amount;
        private String note;
    }

    @Data
    public static class StatusUpdateRequest {
        private String status;
        private String by;
    }

    @Data
    public static class CardResponse {
        private final Long id;
        private final Long customerId;
        private final String customerName;
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
    }
}
