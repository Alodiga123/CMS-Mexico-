package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.application.CardAdminService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.Promotion;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Administrador de Tarjetas: creación de tarjetas ligadas a un cliente
 * (persona natural o jurídica), configuración de límites transaccionales,
 * controles por canal, asignación de promociones y gestión de PIN vía HSM.
 */
@Slf4j
@RestController
@RequestMapping("/api/card-admin")
@RequiredArgsConstructor
public class CardAdminController {

    private final CardAdminService cardAdminService;
    private final CardRepository cardRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping("/cards")
    public ResponseEntity<CardAdminDetailResponse> createCard(@RequestBody CreateCardRequest request) {
        Card card = cardAdminService.createCard(new CardAdminService.CreateCardCommand(
                request.getCustomerId(), request.getProductId(), request.getEmbossedName(),
                request.getCardCategory(), request.getInitialDeposit()));
        return ResponseEntity.ok(toDetail(card));
    }

    @GetMapping("/cards/{id}")
    public ResponseEntity<CardAdminDetailResponse> getCard(@PathVariable Long id) {
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Card", "id", id));
        return ResponseEntity.ok(toDetail(card));
    }

    @PutMapping("/cards/{id}/limits")
    public ResponseEntity<CardAdminDetailResponse> updateLimits(@PathVariable Long id, @RequestBody LimitsRequest request) {
        Card card = cardAdminService.updateTransactionalLimits(id, new CardAdminService.TransactionalLimitsCommand(
                request.getPerTransactionLimit(), request.getDailyLimit(), request.getWeeklyLimit(),
                request.getMonthlyLimit(), request.getAtmDailyLimit()));
        return ResponseEntity.ok(toDetail(card));
    }

    @PutMapping("/cards/{id}/controls")
    public ResponseEntity<CardAdminDetailResponse> updateControls(@PathVariable Long id, @RequestBody ControlsRequest request) {
        Card card = cardAdminService.updateChannelControls(id, new CardAdminService.ChannelControlsCommand(
                request.getOnlinePurchasesEnabled(), request.getInternationalPurchasesEnabled(),
                request.getContactlessEnabled(), request.getAtmWithdrawalsEnabled()));
        return ResponseEntity.ok(toDetail(card));
    }

    @PostMapping("/cards/{id}/promotions/{promotionId}")
    public ResponseEntity<CardAdminDetailResponse> assignPromotion(@PathVariable Long id, @PathVariable Long promotionId) {
        Card card = cardAdminService.assignPromotion(id, promotionId);
        return ResponseEntity.ok(toDetail(card));
    }

    @DeleteMapping("/cards/{id}/promotions/{promotionId}")
    public ResponseEntity<CardAdminDetailResponse> removePromotion(@PathVariable Long id, @PathVariable Long promotionId) {
        Card card = cardAdminService.removePromotion(id, promotionId);
        return ResponseEntity.ok(toDetail(card));
    }

    @PostMapping("/cards/{id}/pin")
    public ResponseEntity<Map<String, Object>> setPin(@PathVariable Long id, @RequestBody PinRequest request) {
        Card card = cardAdminService.setPin(id, request.getPin());
        return ResponseEntity.ok(Map.of(
                "cardId", card.getId(),
                "pinSet", card.isPinSet(),
                "message", "PIN configurado correctamente a través del HSM."
        ));
    }

    @PostMapping("/cards/{id}/pin/verify")
    public ResponseEntity<Map<String, Object>> verifyPin(@PathVariable Long id, @RequestBody PinRequest request) {
        boolean matches = cardAdminService.verifyPin(id, request.getPin());
        return ResponseEntity.ok(Map.of(
                "cardId", id,
                "matches", matches
        ));
    }

    private CardAdminDetailResponse toDetail(Card card) {
        BigDecimal balance = ledgerAccountRepository.findByCard(card)
                .map(ledgerEntryRepository::calculateBalance)
                .orElse(BigDecimal.ZERO);

        return new CardAdminDetailResponse(
                card.getId(),
                card.getCustomer() != null ? card.getCustomer().getId() : null,
                card.getCustomer() != null ? card.getCustomer().getDisplayName() : null,
                card.getCustomer() != null ? card.getCustomer().getCustomerType().name() : null,
                card.getEmbossedName(),
                card.getLast4(),
                card.getStatus().name(),
                card.getProduct() != null ? card.getProduct().getProductName() : "Standard Card",
                balance,
                card.isPinSet(),
                card.getFailedPinAttempts(),
                card.getPerTransactionLimit(),
                card.getEffectiveDailyLimit(),
                card.getEffectiveWeeklyLimit(),
                card.getEffectiveMonthlyLimit(),
                card.getAtmDailyLimit(),
                card.isOnlinePurchasesEnabled(),
                card.isInternationalPurchasesEnabled(),
                card.isContactlessEnabled(),
                card.isAtmWithdrawalsEnabled(),
                card.getAssignedPromotions().stream().map(Promotion::getName).collect(Collectors.toList())
        );
    }

    @Data
    public static class CreateCardRequest {
        @NotNull
        private Long customerId;
        private Long productId;
        private String embossedName;
        private String cardCategory; // PHYSICAL, VIRTUAL
        private BigDecimal initialDeposit;
    }

    @Data
    public static class LimitsRequest {
        private BigDecimal perTransactionLimit;
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal atmDailyLimit;
    }

    @Data
    public static class ControlsRequest {
        private Boolean onlinePurchasesEnabled;
        private Boolean internationalPurchasesEnabled;
        private Boolean contactlessEnabled;
        private Boolean atmWithdrawalsEnabled;
    }

    @Data
    public static class PinRequest {
        private String pin;
    }

    @Data
    public static class CardAdminDetailResponse {
        private final Long cardId;
        private final Long customerId;
        private final String customerName;
        private final String customerType;
        private final String embossedName;
        private final String last4;
        private final String status;
        private final String productName;
        private final BigDecimal balance;
        private final boolean pinSet;
        private final int failedPinAttempts;
        private final BigDecimal perTransactionLimit;
        private final BigDecimal dailyLimit;
        private final BigDecimal weeklyLimit;
        private final BigDecimal monthlyLimit;
        private final BigDecimal atmDailyLimit;
        private final boolean onlinePurchasesEnabled;
        private final boolean internationalPurchasesEnabled;
        private final boolean contactlessEnabled;
        private final boolean atmWithdrawalsEnabled;
        private final List<String> assignedPromotions;
    }
}
