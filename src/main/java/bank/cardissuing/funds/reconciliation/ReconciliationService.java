package bank.cardissuing.funds.reconciliation;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.funds.core.CoreBankingClient;
import bank.cardissuing.funds.core.CoreBankingClient.CoreBalances;
import bank.cardissuing.funds.core.CoreBankingClient.CoreTransaction;
import bank.cardissuing.funds.core.CoreBankingClient.CoreTxType;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationItem.Status;
import bank.cardissuing.funds.reconciliation.ReconciliationItem.Type;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares what the CMS believes about a core account -- its holds, captures and
 * releases -- with the statement the core reports, and keeps the differences as open
 * items until a later run stops seeing them or someone closes them by hand.
 *
 * <p>Matching is exact, not heuristic: a hold is matched by the core transaction id
 * the CMS stored when it reserved; a capture by the id of the core debit, or by the
 * approval code the CMS writes in the debit's note; a core hold is "still active"
 * when the core has not attached a release to it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final CoreBankingClient core;
    private final CardRepository cards;
    private final AuthorizationHoldRepository holds;
    private final ReconciliationItemRepository items;

    /** A difference found in one run, before it is persisted. */
    public record Finding(Type type, Long cardId, String approvalCode, String coreRef,
                          BigDecimal cmsAmount, BigDecimal coreAmount, String detail) {
        String key(String accountId) {
            return type + "|" + accountId + "|" + (approvalCode != null ? approvalCode : "") + "|" + (coreRef != null ? coreRef : "");
        }
    }

    public record RunResult(int accounts, int opened, int stillOpen, int resolved) { }

    public record AccountSummary(String accountId, List<Long> cardIds, BigDecimal cmsHeld,
                                 BigDecimal coreOnHold, BigDecimal coreAvailable, List<ReconciliationItem> openItems) { }

    /** Every core account any card is linked to. */
    @Transactional
    public RunResult reconcileAll() {
        Set<String> accounts = cards.findByExternalAccountIdIsNotNull().stream()
                .map(Card::getExternalAccountId).collect(Collectors.toCollection(TreeSet::new));
        int opened = 0, still = 0, resolved = 0;
        for (String account : accounts) {
            try {
                RunResult r = reconcileAccount(account);
                opened += r.opened(); still += r.stillOpen(); resolved += r.resolved();
            } catch (RuntimeException e) {
                log.warn("Reconciliation of core account {} skipped: {}", account, e.getMessage());
            }
        }
        log.info("Reconciliation: {} accounts, {} new items, {} still open, {} resolved", accounts.size(), opened, still, resolved);
        return new RunResult(accounts.size(), opened, still, resolved);
    }

    @Transactional
    public RunResult reconcileAccount(String accountId) {
        List<Finding> findings = findDifferences(accountId);
        return persist(accountId, findings);
    }

    /** Pure comparison, no persistence: what is wrong with this account right now. */
    public List<Finding> findDifferences(String accountId) {
        List<Card> linked = cards.findByExternalAccountId(accountId);
        List<AuthorizationHold> cmsHolds = linked.stream()
                .flatMap(c -> holds.findByCardOrderByCreatedAtDesc(c).stream()).toList();
        List<CoreTransaction> coreTxs = core.transactions(accountId);
        CoreBalances balances = core.balances(accountId);

        Map<String, CoreTransaction> byId = coreTxs.stream()
                .collect(Collectors.toMap(CoreTransaction::id, Function.identity(), (a, b) -> a));
        List<CoreTransaction> debits = coreTxs.stream()
                .filter(t -> t.type() == CoreTxType.WITHDRAWAL && !t.reversed()).toList();
        Map<String, CoreTransaction> debitsByNote = debits.stream()
                .filter(t -> t.note() != null)
                .collect(Collectors.toMap(CoreTransaction::note, Function.identity(), (a, b) -> a));

        List<Finding> out = new ArrayList<>();
        Set<String> explainedDebits = new HashSet<>();
        BigDecimal cmsHeldTotal = BigDecimal.ZERO;

        for (AuthorizationHold h : cmsHolds) {
            Long cardId = h.getCard().getId();
            CoreTransaction coreHold = h.getExternalRef() != null ? byId.get(h.getExternalRef()) : null;
            boolean coreStillHolds = coreHold != null && coreHold.activeHold();

            switch (h.getStatus()) {
                case HELD -> {
                    cmsHeldTotal = cmsHeldTotal.add(h.getAmount());
                    if (!coreStillHolds) {
                        out.add(new Finding(Type.HOLD_MISSING_IN_CORE, cardId, h.getApprovalCode(), h.getExternalRef(),
                                h.getAmount(), coreHold != null ? coreHold.amount() : null,
                                coreHold == null ? "core has no hold " + h.getExternalRef()
                                                 : "core hold " + h.getExternalRef() + " already released (" + coreHold.releaseRef() + ")"));
                    }
                }
                case CAPTURED -> {
                    CoreTransaction debit = h.getCaptureRef() != null ? byId.get(h.getCaptureRef()) : null;
                    if (debit == null || debit.type() != CoreTxType.WITHDRAWAL || debit.reversed()) {
                        debit = debitsByNote.get(h.getApprovalCode());
                    }
                    if (debit == null) {
                        out.add(new Finding(Type.CAPTURE_MISSING_IN_CORE, cardId, h.getApprovalCode(), h.getCaptureRef(),
                                h.getCapturedAmount(), null, "no core debit for capture " + h.getApprovalCode()));
                    } else {
                        explainedDebits.add(debit.id());
                        if (debit.amount().compareTo(h.getCapturedAmount()) != 0) {
                            out.add(new Finding(Type.AMOUNT_MISMATCH, cardId, h.getApprovalCode(), debit.id(),
                                    h.getCapturedAmount(), debit.amount(),
                                    "CMS captured " + h.getCapturedAmount() + ", core debited " + debit.amount()));
                        }
                    }
                    if (coreStillHolds) {
                        out.add(new Finding(Type.HOLD_NOT_RELEASED, cardId, h.getApprovalCode(), h.getExternalRef(),
                                h.getAmount(), coreHold.amount(), "settled in CMS but core still reserves " + coreHold.amount()));
                    }
                }
                case RELEASED, EXPIRED -> {
                    if (coreStillHolds) {
                        out.add(new Finding(Type.HOLD_NOT_RELEASED, cardId, h.getApprovalCode(), h.getExternalRef(),
                                h.getAmount(), coreHold.amount(),
                                h.getStatus() + " in CMS but core still reserves " + coreHold.amount()));
                    }
                }
            }
        }

        // Debits the core has that no capture explains -- money moved outside the CMS.
        LocalDate since = linked.stream().map(Card::getCreatedAt).filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate).min(LocalDate::compareTo).orElse(LocalDate.MIN);
        for (CoreTransaction d : debits) {
            if (explainedDebits.contains(d.id())) continue;
            if (d.date() != null && d.date().isBefore(since)) continue;
            out.add(new Finding(Type.UNKNOWN_CORE_DEBIT, null, null, d.id(), null, d.amount(),
                    "core debit " + d.id() + " on " + d.date() + (d.note() != null ? " (" + d.note() + ")" : "") + " has no CMS capture"));
        }

        if (cmsHeldTotal.compareTo(balances.onHold()) != 0) {
            out.add(new Finding(Type.HOLD_TOTAL_MISMATCH, null, null, null, cmsHeldTotal, balances.onHold(),
                    "CMS holds " + cmsHeldTotal + ", core reports " + balances.onHold() + " on hold"));
        }
        return out;
    }

    private RunResult persist(String accountId, List<Finding> findings) {
        List<ReconciliationItem> open = items.findByAccountIdAndStatus(accountId, Status.OPEN);
        Map<String, ReconciliationItem> openByKey = open.stream()
                .collect(Collectors.toMap(ReconciliationItem::getItemKey, Function.identity(), (a, b) -> a));
        Set<String> seen = new HashSet<>();
        int opened = 0, still = 0;

        for (Finding f : findings) {
            String key = f.key(accountId);
            seen.add(key);
            ReconciliationItem existing = openByKey.get(key);
            if (existing != null) {
                existing.seenAgain(f.cmsAmount(), f.coreAmount(), f.detail());
                items.save(existing);
                still++;
            } else {
                // A previously resolved item that reappears is a new item: the old one keeps its history.
                items.findByItemKey(key).filter(i -> i.getStatus() == Status.RESOLVED)
                        .ifPresent(i -> { i.setItemKey(i.getItemKey() + "#" + i.getId()); items.save(i); });
                items.save(new ReconciliationItem(key, accountId, f.cardId(), f.approvalCode(), f.coreRef(),
                        f.type(), f.cmsAmount(), f.coreAmount(), f.detail()));
                opened++;
                log.warn("Reconciliation {}: {} on core account {} -- {}", f.type(), f.approvalCode() != null ? f.approvalCode() : f.coreRef(), accountId, f.detail());
            }
        }

        int resolved = 0;
        for (ReconciliationItem i : open) {
            if (!seen.contains(i.getItemKey())) {
                i.resolve("auto: no longer detected");
                items.save(i);
                resolved++;
            }
        }
        return new RunResult(1, opened, still, resolved);
    }

    public AccountSummary summary(String accountId) {
        List<Card> linked = cards.findByExternalAccountId(accountId);
        if (linked.isEmpty()) throw new ResourceNotFoundException("CoreAccount", "id", accountId);
        BigDecimal cmsHeld = linked.stream()
                .map(c -> holds.sumByCardAndStatus(c, HoldStatus.HELD))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CoreBalances b = core.balances(accountId);
        return new AccountSummary(accountId, linked.stream().map(Card::getId).toList(), cmsHeld,
                b.onHold(), b.availableBalance(), items.findByAccountIdAndStatus(accountId, Status.OPEN));
    }

    @Transactional
    public ReconciliationItem resolve(Long id, String note) {
        ReconciliationItem i = items.findById(id).orElseThrow(() -> new ResourceNotFoundException("ReconciliationItem", "id", id));
        i.resolve("manual: " + (note != null && !note.isBlank() ? note : "closed by operator"));
        return items.save(i);
    }
}
