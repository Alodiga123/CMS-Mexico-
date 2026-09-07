package bank.cardissuing.ledger.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final CardRepository cardRepository;

    @GetMapping("/entries")
    public ResponseEntity<List<LedgerEntryResponse>> getLedgerEntries() {
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        List<LedgerEntryResponse> responses = entries.stream().map(entry -> {
            LedgerAccount account = entry.getLedgerAccount();
            Card card = account != null ? account.getCard() : null;
            return new LedgerEntryResponse(
                    entry.getId(),
                    account != null ? account.getId() : null,
                    card != null ? card.getId() : null,
                    card != null ? card.getLast4() : "N/A",
                    entry.getEntryType() != null ? entry.getEntryType().name() : "N/A",
                    entry.getAmount(),
                    entry.getReference(),
                    entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : "N/A"
            );
        }).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @PostMapping("/deposit")
    @Transactional
    public ResponseEntity<?> depositFunds(@RequestBody DepositRequest request) {
        log.info("Processing deposit: cardId={}, amount={}", request.getCardId(), request.getAmount());
        Card card = cardRepository.findById(request.getCardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", "id", request.getCardId()));

        LedgerAccount account = ledgerAccountRepository.findByCard(card)
                .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount", "cardId", card.getId()));

        LedgerEntry entry = new LedgerEntry(
                account,
                EntryType.CREDIT,
                request.getAmount(),
                request.getReference() != null ? request.getReference() : "Manual Deposit"
        );
        ledgerEntryRepository.save(entry);

        BigDecimal newBalance = ledgerEntryRepository.calculateBalance(account);

        return ResponseEntity.ok(Map.of(
                "cardId", card.getId(),
                "depositAmount", request.getAmount(),
                "newBalance", newBalance,
                "message", "Deposit processed successfully"
        ));
    }

    @Data
    public static class DepositRequest {
        private Long cardId;
        private BigDecimal amount;
        private String reference;
    }

    @Data
    public static class LedgerEntryResponse {
        private final Long id;
        private final Long accountId;
        private final Long cardId;
        private final String cardLast4;
        private final String entryType;
        private final BigDecimal amount;
        private final String reference;
        private final String createdAt;
    }
}
