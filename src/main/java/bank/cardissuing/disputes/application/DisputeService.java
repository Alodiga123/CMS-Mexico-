package bank.cardissuing.disputes.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.domain.Dispute.Outcome;
import bank.cardissuing.disputes.domain.Dispute.Status;
import bank.cardissuing.disputes.domain.DisputeEvent;
import bank.cardissuing.disputes.domain.DisputeEvidence;
import bank.cardissuing.disputes.domain.DisputeReason;
import bank.cardissuing.disputes.infrastructure.DisputeEventRepository;
import bank.cardissuing.disputes.infrastructure.DisputeEvidenceRepository;
import bank.cardissuing.disputes.infrastructure.DisputeReasonRepository;
import bank.cardissuing.disputes.infrastructure.DisputeRepository;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * The issuer's side of a dispute: open it against a settled authorization, move it
 * through the chargeback cycle, keep the money right at every step, and build the
 * file the acquirer or an auditor will ask for.
 *
 * <p>Money rules: a provisional credit gives the disputed amount back the day the
 * case opens. If the merchant wins, it is taken back; if the customer wins, it
 * becomes final. A case without provisional credit pays the customer only on a win.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    static final int MAX_EVIDENCE_BYTES = 5 * 1024 * 1024;
    private static final EnumSet<Status> LIVE = EnumSet.of(Status.OPENED, Status.CHARGEBACK_SENT, Status.REPRESENTED);

    private final DisputeRepository disputes;
    private final DisputeReasonRepository reasons;
    private final DisputeEvidenceRepository evidences;
    private final DisputeEventRepository events;
    private final CardRepository cards;
    private final AuthorizationHoldRepository holds;
    private final FundsRouter router;
    private final AuditService audit;

    public record OpenRequest(Long cardId, String approvalCode, BigDecimal amount, String reasonCode,
                              String description, boolean provisionalCredit, String openedBy) { }

    @Transactional
    public Dispute open(OpenRequest r) {
        Card card = cards.findById(r.cardId()).orElseThrow(() -> new ResourceNotFoundException("Card", "id", r.cardId()));
        AuthorizationHold hold = holds.findByApprovalCode(r.approvalCode())
                .orElseThrow(() -> new ResourceNotFoundException("AuthorizationHold", "approvalCode", r.approvalCode()));
        if (!hold.getCard().getId().equals(card.getId())) {
            throw new BusinessException("DISPUTE_CARD_MISMATCH", "Authorization " + r.approvalCode() + " is not on card " + card.getId(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (hold.getStatus() != HoldStatus.CAPTURED) {
            throw new BusinessException("DISPUTE_NOT_SETTLED", "Only a settled (CAPTURED) authorization can be disputed; " + r.approvalCode() + " is " + hold.getStatus(), HttpStatus.CONFLICT);
        }
        if (disputes.existsByApprovalCodeAndStatusIn(r.approvalCode(), LIVE)) {
            throw new BusinessException("DISPUTE_ALREADY_OPEN", "There is already an open dispute on " + r.approvalCode(), HttpStatus.CONFLICT);
        }
        BigDecimal amount = r.amount() != null ? r.amount() : hold.getCapturedAmount();
        if (amount.signum() <= 0 || amount.compareTo(hold.getCapturedAmount()) > 0) {
            throw new BusinessException("DISPUTE_INVALID_AMOUNT", "Disputed amount must be between 0 and the captured " + hold.getCapturedAmount(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        DisputeReason reason = reasons.findByCode(r.reasonCode())
                .orElseThrow(() -> new ResourceNotFoundException("DisputeReason", "code", r.reasonCode()));

        String by = by(r.openedBy());
        Dispute d = new Dispute(card, hold, amount, reason, r.description(), r.provisionalCredit(), by);
        LocalDate txDate = hold.getCreatedAt() != null ? hold.getCreatedAt().toLocalDate() : LocalDate.now();
        d.setTransactionDate(txDate);
        d.setChargebackDeadline(txDate.plusDays(reason.getChargebackDays()));
        d.setResolveBy(LocalDate.now().plusDays(reason.getResolveDays()));
        if (txDate.plusDays(reason.getChargebackDays()).isBefore(LocalDate.now())) {
            d.setDeadlineBreached(true); // opened after the scheme's window: still recorded, flagged from day one
        }

        if (r.provisionalCredit()) {
            String ref = router.forCard(card).credit(card, amount, "PROVISIONAL " + r.approvalCode());
            d.setCreditRef(ref);
        }
        d = disputes.save(d);
        event(d, "OPEN", null, Status.OPENED, r.description(), by);
        audit.log("OPEN_DISPUTE", "Dispute", d.getId().toString(), by);
        log.info("Dispute {} opened on {} for {} ({}), provisional credit {}", d.getId(), r.approvalCode(), amount, reason.getCode(), r.provisionalCredit());
        return d;
    }

    @Transactional
    public Dispute sendChargeback(Long id, String acquirerCaseRef, String by) {
        Dispute d = get(id);
        if (d.getReason().isRequiresEvidence() && evidences.findByDisputeOrderByCreatedAtAsc(d).isEmpty()) {
            throw new BusinessException("DISPUTE_EVIDENCE_REQUIRED",
                    "Reason " + d.getReason().getCode() + " needs at least one evidence before the chargeback", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Status from = d.getStatus();
        d.sendChargeback(LocalDate.now().plusDays(d.getReason().getRepresentmentDays()), acquirerCaseRef);
        event(d, "CHARGEBACK_SENT", from, d.getStatus(), acquirerCaseRef != null ? "acquirer case " + acquirerCaseRef : null, by(by));
        return disputes.save(d);
    }

    @Transactional
    public Dispute represent(Long id, String note, String by) {
        Dispute d = get(id);
        Status from = d.getStatus();
        d.represent();
        event(d, "REPRESENTED", from, d.getStatus(), note, by(by));
        return disputes.save(d);
    }

    @Transactional
    public Dispute resolve(Long id, Outcome outcome, String note, String by) {
        Dispute d = get(id);
        Status from = d.getStatus();
        Card card = d.getCard();
        d.resolve(outcome, note);
        if (outcome == Outcome.MERCHANT && d.isProvisionalCredit()) {
            d.setCreditReversalRef(router.forCard(card).debit(card, d.getAmount(), "REVERSE PROVISIONAL " + d.getApprovalCode()));
        } else if (outcome == Outcome.CUSTOMER && !d.isProvisionalCredit()) {
            d.setCreditRef(router.forCard(card).credit(card, d.getAmount(), "REFUND " + d.getApprovalCode()));
        }
        event(d, "RESOLVED_" + outcome, from, d.getStatus(), note, by(by));
        audit.log("RESOLVE_DISPUTE", "Dispute", d.getId().toString(), by(by));
        log.info("Dispute {} resolved for {} ({})", d.getId(), outcome, note);
        return disputes.save(d);
    }

    @Transactional
    public Dispute withdraw(Long id, String note, String by) {
        Dispute d = get(id);
        Status from = d.getStatus();
        Card card = d.getCard();
        d.withdraw(note);
        if (d.isProvisionalCredit()) {
            d.setCreditReversalRef(router.forCard(card).debit(card, d.getAmount(), "REVERSE PROVISIONAL " + d.getApprovalCode()));
        }
        event(d, "WITHDRAWN", from, d.getStatus(), note, by(by));
        return disputes.save(d);
    }

    @Transactional
    public DisputeEvidence addEvidence(Long id, String filename, String contentType, byte[] content, String description, String by) {
        Dispute d = get(id);
        if (d.getStatus().closed()) {
            throw new BusinessException("DISPUTE_CLOSED", "Dispute " + id + " is " + d.getStatus() + "; no more evidence", HttpStatus.CONFLICT);
        }
        if (content == null || content.length == 0) {
            throw new BusinessException("EVIDENCE_EMPTY", "Evidence has no content", HttpStatus.BAD_REQUEST);
        }
        if (content.length > MAX_EVIDENCE_BYTES) {
            throw new BusinessException("EVIDENCE_TOO_LARGE", "Evidence exceeds " + MAX_EVIDENCE_BYTES + " bytes", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        DisputeEvidence e = evidences.save(new DisputeEvidence(d, filename, contentType, content, sha256(content), description, by(by)));
        event(d, "EVIDENCE_ADDED", null, null, filename + " sha256=" + e.getSha256(), by(by));
        return e;
    }

    public Dispute get(Long id) {
        return disputes.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute", "id", id));
    }

    public List<DisputeEvidence> evidenceOf(Long id) {
        return evidences.findByDisputeOrderByCreatedAtAsc(get(id));
    }

    public List<DisputeReason> reasonCatalog() {
        return reasons.findByActiveTrueOrderByCode();
    }

    /** Live disputes whose current step is due within {@code withinDays}, soonest first. */
    public List<Dispute> due(int withinDays) {
        LocalDate limit = LocalDate.now().plusDays(withinDays);
        return disputes.findByStatusInOrderByCreatedAtAsc(LIVE).stream()
                .filter(d -> d.nextDeadline() != null && !d.nextDeadline().isAfter(limit))
                .sorted(Comparator.comparing(Dispute::nextDeadline))
                .toList();
    }

    /** Marks live disputes whose current step is past due. Returns how many were newly flagged. */
    @Transactional
    public int flagBreached() {
        LocalDate today = LocalDate.now();
        int flagged = 0;
        for (Dispute d : disputes.findByStatusInOrderByCreatedAtAsc(LIVE)) {
            LocalDate due = d.nextDeadline();
            if (due != null && due.isBefore(today) && !d.isDeadlineBreached()) {
                d.setDeadlineBreached(true);
                disputes.save(d);
                event(d, "DEADLINE_BREACHED", null, null, d.getStatus() + " due " + due, "SYSTEM");
                log.warn("Dispute {} ({}) missed its {} deadline {}", d.getId(), d.getApprovalCode(), d.getStatus(), due);
                flagged++;
            }
        }
        return flagged;
    }

    /** The file: everything the acquirer or an auditor needs, in one document. */
    public Map<String, Object> dossier(Long id) {
        Dispute d = get(id);
        Card card = d.getCard();
        AuthorizationHold h = d.getHold();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disputeId", d.getId());
        m.put("status", d.getStatus().name());
        m.put("openedAt", d.getCreatedAt());
        m.put("openedBy", d.getOpenedBy());
        m.put("reason", Map.of("code", d.getReason().getCode(), "description", d.getReason().getDescription(), "network", d.getReason().getNetwork()));
        m.put("amount", d.getAmount());
        m.put("currency", d.getCurrency());
        m.put("description", d.getDescription());
        m.put("card", mapOf("cardId", card.getId(), "last4", card.getLast4(),
                "product", card.getProduct() != null ? card.getProduct().getProductName() : null,
                "customerId", card.getCustomer() != null ? card.getCustomer().getId() : null));
        m.put("authorization", h == null ? null : mapOf("approvalCode", h.getApprovalCode(), "amount", h.getAmount(),
                "capturedAmount", h.getCapturedAmount(), "merchantName", h.getMerchantName(), "merchantId", h.getMerchantId(),
                "transactionType", h.getTransactionType(), "authorizedAt", h.getCreatedAt(), "coreRef", h.getExternalRef(), "coreCaptureRef", h.getCaptureRef()));
        m.put("money", mapOf("provisionalCredit", d.isProvisionalCredit(), "creditRef", d.getCreditRef(), "creditReversalRef", d.getCreditReversalRef()));
        m.put("deadlines", mapOf("transactionDate", d.getTransactionDate(), "chargebackBy", d.getChargebackDeadline(),
                "representmentBy", d.getRepresentmentDeadline(), "resolveBy", d.getResolveBy(), "next", d.nextDeadline(), "breached", d.isDeadlineBreached()));
        m.put("acquirerCaseRef", d.getAcquirerCaseRef());
        m.put("outcome", d.getOutcome() != null ? d.getOutcome().name() : null);
        m.put("resolutionNote", d.getResolutionNote());
        m.put("evidence", evidences.findByDisputeOrderByCreatedAtAsc(d).stream().map(e -> mapOf(
                "id", e.getId(), "filename", e.getFilename(), "contentType", e.getContentType(), "size", e.getSize(),
                "sha256", e.getSha256(), "description", e.getDescription(), "addedAt", e.getCreatedAt(), "addedBy", e.getAddedBy())).toList());
        m.put("timeline", events.findByDisputeOrderByCreatedAtAsc(d).stream().map(ev -> mapOf(
                "at", ev.getCreatedAt(), "action", ev.getAction(), "from", ev.getFromStatus(), "to", ev.getToStatus(),
                "note", ev.getNote(), "by", ev.getPerformedBy())).toList());
        return m;
    }

    private void event(Dispute d, String action, Status from, Status to, String note, String by) {
        events.save(new DisputeEvent(d, action, from, to, note, by));
    }

    private static String by(String by) {
        return by != null && !by.isBlank() ? by : "SYSTEM";
    }

    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Map.of rejects nulls; the file has plenty of legitimately empty fields. */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
