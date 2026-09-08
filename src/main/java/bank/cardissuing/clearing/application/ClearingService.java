package bank.cardissuing.clearing.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.application.PanVault;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.clearing.application.ClearingFileParser.Line;
import bank.cardissuing.clearing.application.ClearingFileParser.Parsed;
import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.ClearingRecord;
import bank.cardissuing.clearing.domain.ClearingRecord.Outcome;
import bank.cardissuing.clearing.infrastructure.ClearingBatchRepository;
import bank.cardissuing.clearing.infrastructure.ClearingRecordRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.CmsPrincipal;
import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.infrastructure.DisputeRepository;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationItem;
import bank.cardissuing.funds.reconciliation.ReconciliationItemRepository;
import bank.cardissuing.reports.application.ReportCsv;
import bank.cardissuing.transaction.application.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Clearing: what the network says was really spent, against what the authorizer
 * reserved. A presentment that matches a live hold settles it (the money moves now);
 * one that does not match anything is posted anyway and flagged, because the issuer
 * owes the network regardless; reversals credit back; chargebacks and representments
 * find their dispute; interchange is booked. Every line ends with an outcome, and every
 * exception becomes a reconciliation item somebody has to close.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClearingService {

    private static final Set<Outcome> EXCEPTIONS = EnumSet.of(Outcome.AMOUNT_MISMATCH, Outcome.ALREADY_CAPTURED, Outcome.FORCE_POSTED, Outcome.NO_CARD, Outcome.UNMATCHED, Outcome.ERROR);

    private final ClearingBatchRepository batches;
    private final ClearingRecordRepository records;
    private final AuthorizationHoldRepository holds;
    private final CardRepository cards;
    private final PanVault vault;
    private final AuthorizationService authorizer;
    private final FundsRouter router;
    private final DisputeRepository disputes;
    private final ReconciliationItemRepository items;
    private final SettlementService settlement;
    private final ClearingSettings settings;
    private final AuditService audit;

    /** Loads a file, processes every record, adds the batch to its settlement cycle. */
    public ClearingBatch ingest(String fileName, String content, String by) {
        String sha = ReportCsv.sha256(content);
        Optional<ClearingBatch> dup = batches.findBySha256(sha);
        if (dup.isPresent()) throw new BusinessException("CLEARING_FILE_DUPLICATE", "This exact file was already loaded as batch " + dup.get().getId(), HttpStatus.CONFLICT);
        Parsed parsed = ClearingFileParser.parse(content);
        ClearingBatch b = new ClearingBatch();
        b.setNetwork(parsed.header().network());
        b.setFileName(fileName != null ? fileName : parsed.header().fileId());
        b.setFileId(parsed.header().fileId());
        b.setCycleDate(parsed.header().cycleDate());
        b.setSha256(sha);
        b.setRecordCount(parsed.lines().size());
        b.setTrailerCount(parsed.trailer().count());
        b.setTrailerAmount(parsed.trailer().totalAmount());
        b.setLoadedBy(CmsPrincipal.auditName(by));
        b.setContent(content);
        b = batches.save(b);
        audit.log("CLEARING_FILE_LOADED", "ClearingBatch", b.getId().toString(), b.getLoadedBy());

        int matched = 0, exceptions = 0, presentments = 0;
        BigDecimal pres = BigDecimal.ZERO, rev = BigDecimal.ZERO, cb = BigDecimal.ZERO, fees = BigDecimal.ZERO, interchange = BigDecimal.ZERO;
        for (Line l : parsed.lines()) {
            ClearingRecord r = process(b, l);
            if (EXCEPTIONS.contains(r.getOutcome())) exceptions++; else matched++;
            switch (l.type()) {
                case PRESENTMENT -> { if (r.getOutcome() != Outcome.NO_CARD && r.getOutcome() != Outcome.ALREADY_CAPTURED) { presentments++; pres = pres.add(l.amount()); } if (l.interchangeFee() != null && r.getOutcome() != Outcome.NO_CARD && r.getOutcome() != Outcome.ALREADY_CAPTURED) interchange = interchange.add(l.interchangeFee()); }
                case REVERSAL -> { if (r.getOutcome() == Outcome.REVERSED) rev = rev.add(l.amount()); }
                case CHARGEBACK -> { if (r.getOutcome() == Outcome.DISPUTE_LINKED) cb = cb.add(l.amount()); }
                case FEE -> fees = fees.add(l.amount());
                default -> { }
            }
        }
        b.setMatchedCount(matched);
        b.setExceptionCount(exceptions);
        b.setPresentmentsCount(presentments);
        b.setPresentmentsAmount(pres);
        b.setReversalsAmount(rev);
        b.setChargebacksAmount(cb);
        b.setFeesAmount(fees);
        b.setInterchangeAmount(interchange);
        b.setStatus(ClearingBatch.Status.PROCESSED);
        b.setProcessedAt(LocalDateTime.now());
        try {
            b.setSettlementCycleId(settlement.addBatch(b).getId());
        } catch (BusinessException e) {
            // the records are posted (each in its own transaction); the batch stays visible as FAILED with the reason
            b.setStatus(ClearingBatch.Status.FAILED);
            b.setError(e.getMessage());
            batches.save(b);
            throw e;
        }
        b = batches.save(b);
        audit.log("CLEARING_FILE_PROCESSED", "ClearingBatch", b.getId().toString(), b.getLoadedBy());
        log.info("Clearing batch #{} {} {}: {} records, {} matched, {} exceptions, presentments {} interchange {} fees {}", b.getId(), b.getNetwork(), b.getCycleDate(), b.getRecordCount(), matched, exceptions, pres, interchange, fees);
        return b;
    }

    /** One record, its own transaction: a failure on one line never undoes the others. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected ClearingRecord process(ClearingBatch b, Line l) {
        ClearingRecord r = new ClearingRecord();
        r.setBatch(b); r.setLineNo(l.lineNo()); r.setType(l.type());
        r.setPanMasked(l.pan() != null ? PanVault.mask(l.pan()) : null);
        r.setRrn(l.rrn()); r.setStan(l.stan()); r.setApprovalId(l.approvalId());
        r.setAmount(l.amount()); r.setCurrency(l.currency()); r.setInterchangeFee(l.interchangeFee());
        r.setMcc(l.mcc()); r.setMerchantId(l.merchantId()); r.setMerchantName(l.merchantName());
        r.setTransactionDate(l.txDate()); r.setReasonCode(l.reasonCode());
        try {
            switch (l.type()) {
                case PRESENTMENT -> presentment(r, l);
                case REVERSAL -> reversal(r, l);
                case CHARGEBACK, REPRESENTMENT -> chargeback(r, l);
                case FEE -> { r.setOutcome(Outcome.FEE_BOOKED); r.setDetail("interchange / network fee"); }
            }
        } catch (RuntimeException e) {
            r.setOutcome(Outcome.ERROR);
            r.setDetail(("error: " + e.getMessage()).substring(0, Math.min(400, ("error: " + e.getMessage()).length())));
            log.warn("Clearing line {} of batch {} failed: {}", l.lineNo(), b.getId(), e.getMessage());
        }
        return records.save(r);
    }

    private void presentment(ClearingRecord r, Line l) {
        Optional<Card> card = findCard(l);
        if (card.isEmpty()) { r.setOutcome(Outcome.NO_CARD); r.setDetail("PAN unknown"); item(r, ReconciliationItem.Type.CLEARING_NO_CARD, null, "presentment for an unknown PAN " + r.getPanMasked()); return; }
        r.setCardId(card.get().getId());
        Optional<AuthorizationHold> hold = findHold(l, card.get());
        if (hold.isEmpty()) {
            forcePost(r, card.get(), l, "no authorization found for rrn " + l.rrn() + " / approval " + l.approvalId());
            return;
        }
        AuthorizationHold h = hold.get();
        r.setApprovalCode(h.getApprovalCode());
        if (h.getStatus() == HoldStatus.CAPTURED) {
            r.setOutcome(Outcome.ALREADY_CAPTURED); r.setDetail("already settled (" + h.getCapturedAmount() + ")");
            item(r, ReconciliationItem.Type.CLEARING_DUPLICATE, h, "second presentment of " + h.getApprovalCode());
            return;
        }
        if (h.getStatus() != HoldStatus.HELD) {
            forcePost(r, card.get(), l, "authorization " + h.getApprovalCode() + " was " + h.getStatus() + " when presented");
            return;
        }
        BigDecimal tolerance = h.getAmount().multiply(settings.getTolerancePercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal ceiling = h.getAmount().add(tolerance);
        if (l.amount().compareTo(ceiling) > 0) {
            // capture what was reserved (the most the hold can settle), flag the rest
            authorizer.capture(h.getApprovalCode(), h.getAmount());
            BigDecimal extra = l.amount().subtract(h.getAmount());
            r.setOutcome(Outcome.AMOUNT_MISMATCH);
            r.setDetail("presented " + l.amount() + " against " + h.getAmount() + " held (tolerance " + settings.getTolerancePercent() + "%): " + extra + " left to reconcile");
            item(r, ReconciliationItem.Type.CLEARING_AMOUNT_MISMATCH, h, r.getDetail());
            return;
        }
        BigDecimal toCapture = l.amount().min(h.getAmount()).max(l.amount().compareTo(h.getAmount()) > 0 ? h.getAmount() : l.amount());
        if (l.amount().compareTo(h.getAmount()) > 0) {
            // within tolerance but above the hold: settle the hold in full and post the tip / difference as a debit
            authorizer.capture(h.getApprovalCode(), h.getAmount());
            BigDecimal extra = l.amount().subtract(h.getAmount());
            router.forCard(card.get()).debit(card.get(), extra, "CLEARING-TOLERANCE-" + h.getApprovalCode());
            r.setOutcome(Outcome.MATCHED_CAPTURED);
            r.setDetail("settled " + h.getAmount() + " plus " + extra + " within tolerance");
            return;
        }
        authorizer.capture(h.getApprovalCode(), toCapture);
        r.setOutcome(Outcome.MATCHED_CAPTURED);
        r.setDetail(toCapture.compareTo(h.getAmount()) < 0 ? "partial: " + toCapture + " of " + h.getAmount() : "settled");
    }

    private void forcePost(ClearingRecord r, Card card, Line l, String why) {
        AuthorizationHold h = new AuthorizationHold(card, "CLR-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                l.amount(), l.merchantName() != null ? l.merchantName() : "Clearing", l.merchantId(), "PURCHASE", LocalDateTime.now());
        h.setRrn(l.rrn()); h.setStan(l.stan()); h.setNetworkAdvice(true);
        h.capture(l.amount());
        router.forCard(card).capture(card, h);
        holds.save(h);
        r.setApprovalCode(h.getApprovalCode());
        r.setOutcome(Outcome.FORCE_POSTED);
        r.setDetail("posted without live authorization: " + why);
        item(r, ReconciliationItem.Type.CLEARING_NO_AUTHORIZATION, h, r.getDetail());
    }

    private void reversal(ClearingRecord r, Line l) {
        Optional<Card> card = findCard(l);
        if (card.isEmpty()) { r.setOutcome(Outcome.NO_CARD); r.setDetail("PAN unknown"); item(r, ReconciliationItem.Type.CLEARING_NO_CARD, null, "reversal for an unknown PAN"); return; }
        r.setCardId(card.get().getId());
        Optional<AuthorizationHold> hold = findHold(l, card.get());
        if (hold.isEmpty() || hold.get().getStatus() != HoldStatus.CAPTURED) {
            if (hold.isPresent() && hold.get().getStatus() == HoldStatus.HELD) {
                authorizer.reverse(hold.get().getApprovalCode());
                r.setApprovalCode(hold.get().getApprovalCode()); r.setOutcome(Outcome.REVERSED); r.setDetail("hold released");
                return;
            }
            r.setOutcome(Outcome.UNMATCHED); r.setDetail("no captured authorization to reverse");
            item(r, ReconciliationItem.Type.CLEARING_UNMATCHED, hold.orElse(null), "clearing reversal without a settled authorization");
            return;
        }
        AuthorizationHold h = hold.get();
        router.forCard(card.get()).credit(card.get(), l.amount(), "CLEARING-REVERSAL-" + h.getApprovalCode());
        r.setApprovalCode(h.getApprovalCode());
        r.setOutcome(Outcome.REVERSED);
        r.setDetail("credited " + l.amount() + " back");
    }

    private void chargeback(ClearingRecord r, Line l) {
        Optional<Card> card = findCard(l);
        card.ifPresent(c -> r.setCardId(c.getId()));
        Optional<AuthorizationHold> hold = card.flatMap(c -> findHold(l, c));
        Optional<Dispute> dispute = hold.flatMap(h -> disputes.findFirstByApprovalCodeOrderByCreatedAtDesc(h.getApprovalCode()));
        if (dispute.isEmpty()) {
            r.setOutcome(Outcome.UNMATCHED); r.setDetail(l.type() + " " + l.reasonCode() + " with no dispute on our side");
            item(r, ReconciliationItem.Type.CLEARING_UNMATCHED, hold.orElse(null), r.getDetail());
            return;
        }
        Dispute d = dispute.get();
        r.setApprovalCode(d.getApprovalCode());
        r.setOutcome(Outcome.DISPUTE_LINKED);
        r.setDetail(l.type() + " " + l.reasonCode() + " on dispute #" + d.getId() + " (" + d.getStatus() + ")");
    }

    // ------------------------------------------------------------ lookups

    private Optional<Card> findCard(Line l) {
        if (l.pan() == null) return Optional.empty();
        return cards.findByPanHash(vault.hash(l.pan()));
    }

    private Optional<AuthorizationHold> findHold(Line l, Card card) {
        Optional<AuthorizationHold> h = Optional.empty();
        if (l.rrn() != null) h = holds.findFirstByRrnOrderByCreatedAtDesc(l.rrn());
        if (h.isEmpty() && l.approvalId() != null) h = holds.findFirstByApprovalCodeEndingWithOrderByCreatedAtDesc(l.approvalId());
        return h.filter(x -> x.getCard().getId().equals(card.getId()));
    }

    private void item(ClearingRecord r, ReconciliationItem.Type type, AuthorizationHold h, String detail) {
        String key = "CLEARING:" + r.getBatch().getId() + ":" + r.getLineNo();
        if (items.findByItemKey(key).isPresent()) return;
        ReconciliationItem it = new ReconciliationItem();
        it.setItemKey(key);
        it.setType(type);
        it.setCardId(r.getCardId());
        it.setAccountId(h != null && h.getCard().getExternalAccountId() != null ? h.getCard().getExternalAccountId() : r.getCardId() != null ? "CARD-" + r.getCardId() : "NETWORK-" + r.getBatch().getNetwork());
        it.setApprovalCode(h != null ? h.getApprovalCode() : null);
        it.setCmsAmount(h != null ? h.getAmount() : BigDecimal.ZERO);
        it.setCoreAmount(r.getAmount());
        it.setDetail(detail.length() > 480 ? detail.substring(0, 480) : detail);
        it.setLastSeenAt(LocalDateTime.now());
        items.save(it);
    }

    // ------------------------------------------------------------ queries

    public List<ClearingBatch> recent() { return batches.findTop100ByOrderByCreatedAtDesc(); }

    public ClearingBatch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> new BusinessException("CLEARING_BATCH_NOT_FOUND", "Batch " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public List<ClearingRecord> recordsOf(Long id, Outcome outcome) {
        ClearingBatch b = batch(id);
        return outcome == null ? records.findByBatchOrderByLineNoAsc(b) : records.findByBatchAndOutcomeOrderByLineNoAsc(b, outcome);
    }

    public List<ClearingRecord> exceptions() { return records.findTop200ByOutcomeInOrderByCreatedAtDesc(EXCEPTIONS); }
}
