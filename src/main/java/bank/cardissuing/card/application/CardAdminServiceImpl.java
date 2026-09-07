package bank.cardissuing.card.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardCategory;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.card.domain.Promotion;
import bank.cardissuing.card.exception.PinPolicyViolationException;
import bank.cardissuing.card.exception.PinRetriesExceededException;
import bank.cardissuing.card.infrastructure.CardProductRepository;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.card.infrastructure.PromotionRepository;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.hsm.infrastructure.HsmService;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardAdminServiceImpl implements CardAdminService {

    private final CardRepository cardRepository;
    private final CardProductRepository cardProductRepository;
    private final CustomerRepository customerRepository;
    private final PromotionRepository promotionRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final HsmService hsmService;
    private final AuditService auditService;

    @Override
    @Transactional
    public Card createCard(CreateCardCommand command) {
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", command.customerId()));

        CardProduct product = null;
        if (command.productId() != null) {
            product = cardProductRepository.findById(command.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("CardProduct", "id", command.productId()));
        }

        String last4 = String.format("%04d", new Random().nextInt(10000));

        Card card = new Card();
        card.setCustomer(customer);
        card.setProduct(product);
        card.setEmbossedName(command.embossedName() != null && !command.embossedName().isBlank()
                ? command.embossedName().toUpperCase()
                : customer.getDisplayName().toUpperCase());
        card.setLast4(last4);
        card.setStatus(CardStatus.CREATED);
        card.setCardCategory(command.cardCategory() != null
                ? CardCategory.valueOf(command.cardCategory().toUpperCase())
                : CardCategory.PHYSICAL);
        card.setExpiryDate(LocalDate.now().plusYears(3));
        card = cardRepository.save(card);

        LedgerAccount account = new LedgerAccount();
        account.setCard(card);
        account.setCurrency(product != null ? product.getCurrency() : "USD");
        account = ledgerAccountRepository.save(account);

        BigDecimal initialDeposit = command.initialDeposit() != null ? command.initialDeposit() : BigDecimal.ZERO;
        if (initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            ledgerEntryRepository.save(new LedgerEntry(account, EntryType.CREDIT, initialDeposit,
                    "Depósito Inicial - Alta desde Administrador de Tarjetas"));
        }

        auditService.log("CARD_ADMIN_CREATE_CARD", "Card", card.getId().toString(), "CARD_ADMIN");
        log.info("Card created via Card Administrator: cardId={}, customerId={}", card.getId(), customer.getId());
        return card;
    }

    @Override
    @Transactional
    public Card updateTransactionalLimits(Long cardId, TransactionalLimitsCommand command) {
        Card card = getCard(cardId);

        if (command.perTransactionLimit() != null) card.setPerTransactionLimit(command.perTransactionLimit());
        if (command.dailyLimit() != null) card.setDailyLimitOverride(command.dailyLimit());
        if (command.weeklyLimit() != null) card.setWeeklyLimitOverride(command.weeklyLimit());
        if (command.monthlyLimit() != null) card.setMonthlyLimitOverride(command.monthlyLimit());
        if (command.atmDailyLimit() != null) card.setAtmDailyLimit(command.atmDailyLimit());

        card = cardRepository.save(card);
        auditService.log("CARD_ADMIN_SET_LIMITS", "Card", card.getId().toString(), "CARD_ADMIN");
        return card;
    }

    @Override
    @Transactional
    public Card updateChannelControls(Long cardId, ChannelControlsCommand command) {
        Card card = getCard(cardId);

        if (command.onlinePurchasesEnabled() != null) card.setOnlinePurchasesEnabled(command.onlinePurchasesEnabled());
        if (command.internationalPurchasesEnabled() != null) card.setInternationalPurchasesEnabled(command.internationalPurchasesEnabled());
        if (command.contactlessEnabled() != null) card.setContactlessEnabled(command.contactlessEnabled());
        if (command.atmWithdrawalsEnabled() != null) card.setAtmWithdrawalsEnabled(command.atmWithdrawalsEnabled());

        card = cardRepository.save(card);
        auditService.log("CARD_ADMIN_SET_CONTROLS", "Card", card.getId().toString(), "CARD_ADMIN");
        return card;
    }

    @Override
    @Transactional
    public Card assignPromotion(Long cardId, Long promotionId) {
        Card card = getCard(cardId);
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", "id", promotionId));

        card.assignPromotion(promotion);
        card = cardRepository.save(card);
        auditService.log("CARD_ADMIN_ASSIGN_PROMOTION", "Card", card.getId().toString(), "CARD_ADMIN");
        return card;
    }

    @Override
    @Transactional
    public Card removePromotion(Long cardId, Long promotionId) {
        Card card = getCard(cardId);
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", "id", promotionId));

        card.removePromotion(promotion);
        card = cardRepository.save(card);
        auditService.log("CARD_ADMIN_REMOVE_PROMOTION", "Card", card.getId().toString(), "CARD_ADMIN");
        return card;
    }

    @Override
    @Transactional
    public Card setPin(Long cardId, String pin) {
        Card card = getCard(cardId);
        validatePinPolicy(pin);

        String bin = card.getProduct() != null ? card.getProduct().getBin() : "453211";
        String format = card.getProduct() != null ? card.getProduct().getPinBlockFormat() : "ISO-0";
        String pvk = card.getProduct() != null ? card.getProduct().getPvkIndex() : "PVK-01";

        // El PIN en claro nunca se persiste: se envía al HSM (simulador PayShield) para
        // obtener el bloque PIN cifrado bajo la PVK del producto. El hash local solo
        // sirve para simular la verificación offline dentro de este CMS de laboratorio;
        // en un HSM real la verificación se delega por completo a su función Verify PIN.
        HsmService.HsmCardCryptoResult crypto = hsmService.generateCardCryptograms(bin, card.getLast4(), format, pvk);

        card.assignPin(crypto.getPinBlock(), crypto.getKcvPvk(), hashPin(cardId, pin));
        card = cardRepository.save(card);

        auditService.log("CARD_ADMIN_SET_PIN", "Card", card.getId().toString(), "CARD_ADMIN");
        log.info("PIN configured for card {} via HSM PayShield Simulator (PVK={})", cardId, pvk);
        return card;
    }

    @Override
    @Transactional
    public boolean verifyPin(Long cardId, String pin) {
        Card card = getCard(cardId);
        if (!card.isPinSet()) {
            throw new PinPolicyViolationException("Card " + cardId + " does not have a PIN configured yet.");
        }

        boolean matches = card.getPinHash() != null && card.getPinHash().equals(hashPin(cardId, pin));
        if (matches) {
            card.resetPinAttempts();
            cardRepository.save(card);
            return true;
        }

        card.registerFailedPinAttempt();
        cardRepository.save(card);
        if (card.getStatus() == CardStatus.BLOCKED) {
            auditService.log("CARD_ADMIN_PIN_BLOCKED", "Card", card.getId().toString(), "CARD_ADMIN");
            throw new PinRetriesExceededException(cardId);
        }
        return false;
    }

    private void validatePinPolicy(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new PinPolicyViolationException("PIN must be exactly 4 numeric digits.");
        }
        if (pin.chars().distinct().count() == 1) {
            throw new PinPolicyViolationException("PIN cannot use the same digit repeated (e.g. 1111).");
        }
        if ("0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw new PinPolicyViolationException("PIN cannot be a sequential number (e.g. 1234, 4321).");
        }
    }

    private String hashPin(Long cardId, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("CARD-" + cardId + ":" + pin).getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Card getCard(Long cardId) {
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
    }
}
