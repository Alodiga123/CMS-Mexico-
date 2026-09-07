package bank.cardissuing.ledger.infrastructure;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.InsufficientFundsException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/accounting")
@RequiredArgsConstructor
public class AccountingController {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final CardRepository cardRepository;
    private final CustomerRepository customerRepository;
    private final AuditService auditService;

    @GetMapping("/statement")
    public ResponseEntity<AccountStatementResponse> getAccountStatement(
            @RequestParam(required = false) Long cardId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String type
    ) {
        log.info("Generating accounting statement: cardId={}, customerId={}, startDate={}, endDate={}, type={}",
                cardId, customerId, startDate, endDate, type);

        LedgerAccount targetAccount = null;
        String customerName = "Todos los Clientes";
        String cardLast4 = "Todas";

        if (cardId != null) {
            Card card = cardRepository.findById(cardId)
                    .orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
            targetAccount = ledgerAccountRepository.findByCard(card)
                    .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount", "cardId", cardId));
            cardLast4 = card.getLast4();
            if (card.getCustomer() != null) {
                customerName = card.getCustomer().getFullName();
            }
        } else if (customerId != null) {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));
            customerName = customer.getFullName();
            Card firstCard = cardRepository.findByCustomer(customer).stream().findFirst().orElse(null);
            if (firstCard != null) {
                targetAccount = ledgerAccountRepository.findByCard(firstCard).orElse(null);
                cardLast4 = firstCard.getLast4();
            }
        }

        List<LedgerEntry> rawEntries = ledgerEntryRepository.findWithAccountFilter(targetAccount);

        // Apply Date Range and Entry Type Filtering in Java
        List<LedgerEntry> filteredEntries = rawEntries.stream()
                .filter(e -> startDate == null || (e.getCreatedAt() != null && !e.getCreatedAt().isBefore(startDate)))
                .filter(e -> endDate == null || (e.getCreatedAt() != null && !e.getCreatedAt().isAfter(endDate)))
                .filter(e -> type == null || type.isBlank() || type.equalsIgnoreCase("ALL") || e.getEntryType().name().equalsIgnoreCase(type))
                .collect(Collectors.toList());

        BigDecimal totalCredits = filteredEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalDebits = filteredEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentBalance = targetAccount != null
                ? ledgerEntryRepository.calculateBalance(targetAccount)
                : totalCredits.subtract(totalDebits);

        List<AccountingMovementDto> movements = filteredEntries.stream().map(entry -> {
            LedgerAccount acc = entry.getLedgerAccount();
            Card card = acc != null ? acc.getCard() : null;
            Customer cust = card != null ? card.getCustomer() : null;
            return new AccountingMovementDto(
                    entry.getId(),
                    cust != null ? cust.getFullName() : "N/A",
                    card != null ? card.getId() : null,
                    card != null ? card.getLast4() : "N/A",
                    entry.getEntryType().name(),
                    entry.getAmount(),
                    entry.getReference(),
                    entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : "N/A"
            );
        }).collect(Collectors.toList());

        AccountStatementResponse response = new AccountStatementResponse(
                customerName,
                cardLast4,
                totalCredits,
                totalDebits,
                currentBalance,
                movements.size(),
                movements
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/movement")
    @Transactional
    public ResponseEntity<?> processAccountingMovement(@RequestBody MovementRequest request) {
        log.info("Processing Core Accounting Movement: cardId={}, type={}, amount={}, concept={}",
                request.getCardId(), request.getType(), request.getAmount(), request.getConcept());

        Card card = cardRepository.findById(request.getCardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", "id", request.getCardId()));

        LedgerAccount account = ledgerAccountRepository.findByCard(card)
                .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount", "cardId", card.getId()));

        EntryType entryType = EntryType.valueOf(request.getType().toUpperCase());
        BigDecimal amount = request.getAmount();

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Monto debe ser mayor a cero");
        }

        BigDecimal currentBalance = ledgerEntryRepository.calculateBalance(account);

        if (entryType == EntryType.DEBIT && amount.compareTo(currentBalance) > 0) {
            throw new InsufficientFundsException(amount, currentBalance);
        }

        String reference = request.getConcept() != null && !request.getConcept().isBlank()
                ? request.getConcept()
                : (entryType == EntryType.CREDIT ? "Abono Contable Core" : "Cargo Contable Core");

        LedgerEntry entry = new LedgerEntry(account, entryType, amount, reference);
        ledgerEntryRepository.save(entry);

        BigDecimal newBalance = ledgerEntryRepository.calculateBalance(account);

        auditService.log(
                "ACCOUNTING_MOVEMENT_" + entryType.name(),
                "LedgerAccount",
                account.getId().toString(),
                "SYSTEM"
        );

        return ResponseEntity.ok(Map.of(
                "movementId", entry.getId(),
                "cardId", card.getId(),
                "type", entryType.name(),
                "amount", amount,
                "previousBalance", currentBalance,
                "newBalance", newBalance,
                "reference", reference,
                "message", "Movimiento contable procesado con éxito en el Core Bancario"
        ));
    }

    @Data
    public static class MovementRequest {
        private Long cardId;
        private String type; // CREDIT, DEBIT
        private BigDecimal amount;
        private String concept;
    }

    @Data
    public static class AccountStatementResponse {
        private final String customerName;
        private final String cardLast4;
        private final BigDecimal totalCredits;
        private final BigDecimal totalDebits;
        private final BigDecimal currentBalance;
        private final int totalMovements;
        private final List<AccountingMovementDto> movements;
    }

    @Data
    public static class AccountingMovementDto {
        private final Long id;
        private final String customerName;
        private final Long cardId;
        private final String cardLast4;
        private final String type;
        private final BigDecimal amount;
        private final String reference;
        private final String timestamp;
    }
}
