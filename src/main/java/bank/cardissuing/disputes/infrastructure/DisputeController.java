package bank.cardissuing.disputes.infrastructure;

import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.disputes.application.DisputeService;
import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.domain.DisputeEvidence;
import bank.cardissuing.disputes.domain.DisputeReason;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aclaraciones: the customer's claim, the chargeback cycle, the file. */
@RestController
@RequestMapping("/api/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService service;
    private final DisputeRepository disputes;
    private final CardRepository cards;

    public record OpenBody(Long cardId, String approvalCode, BigDecimal amount, String reasonCode,
                           String description, Boolean provisionalCredit, String openedBy) { }
    public record EvidenceBody(String filename, String contentType, String contentBase64, String text,
                               String description, String by) { }
    public record NoteBody(String note, String by, String acquirerCaseRef, String outcome) { }

    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@RequestBody OpenBody b) {
        Dispute d = service.open(new DisputeService.OpenRequest(b.cardId(), b.approvalCode(), b.amount(), b.reasonCode(),
                b.description(), Boolean.TRUE.equals(b.provisionalCredit()), b.openedBy()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseEntity.ok(view(service.get(id)));
    }

    /** All, or filtered by ?status= and/or ?cardId=. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                          @RequestParam(required = false) Long cardId) {
        List<Dispute> list;
        if (cardId != null) {
            list = disputes.findByCardOrderByCreatedAtDesc(cards.findById(cardId)
                    .orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId)));
            if (status != null) list = list.stream().filter(d -> d.getStatus().name().equalsIgnoreCase(status)).toList();
        } else if (status != null) {
            list = disputes.findByStatusOrderByCreatedAtDesc(Dispute.Status.valueOf(status.toUpperCase()));
        } else {
            list = disputes.findAllByOrderByCreatedAtDesc();
        }
        return ResponseEntity.ok(list.stream().map(DisputeController::view).toList());
    }

    @GetMapping("/due")
    public ResponseEntity<List<Map<String, Object>>> due(@RequestParam(defaultValue = "3") int withinDays) {
        return ResponseEntity.ok(service.due(withinDays).stream().map(DisputeController::view).toList());
    }

    @GetMapping("/reasons")
    public ResponseEntity<List<Map<String, Object>>> reasons() {
        return ResponseEntity.ok(service.reasonCatalog().stream().map(DisputeController::view).toList());
    }

    @PostMapping("/{id}/evidence")
    public ResponseEntity<Map<String, Object>> addEvidence(@PathVariable Long id, @RequestBody EvidenceBody b) {
        byte[] content;
        if (b.contentBase64() != null && !b.contentBase64().isBlank()) {
            try { content = Base64.getDecoder().decode(b.contentBase64()); }
            catch (IllegalArgumentException e) { throw new BusinessException("EVIDENCE_BAD_BASE64", "contentBase64 is not valid base64", HttpStatus.BAD_REQUEST); }
        } else if (b.text() != null) {
            content = b.text().getBytes(StandardCharsets.UTF_8);
        } else {
            content = new byte[0];
        }
        DisputeEvidence e = service.addEvidence(id, b.filename(), b.contentType(), content, b.description(), b.by());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(e));
    }

    @GetMapping("/{id}/evidence")
    public ResponseEntity<List<Map<String, Object>>> evidence(@PathVariable Long id) {
        return ResponseEntity.ok(service.evidenceOf(id).stream().map(DisputeController::view).toList());
    }

    @GetMapping("/{id}/evidence/{evidenceId}/content")
    public ResponseEntity<byte[]> evidenceContent(@PathVariable Long id, @PathVariable Long evidenceId) {
        DisputeEvidence e = service.evidenceOf(id).stream().filter(x -> x.getId().equals(evidenceId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("DisputeEvidence", "id", evidenceId));
        MediaType type = e.getContentType() != null ? MediaType.parseMediaType(e.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type)
                .header("Content-Disposition", "inline; filename=\"" + e.getFilename() + "\"")
                .header("X-Content-SHA256", e.getSha256())
                .body(e.getContent());
    }

    @PostMapping("/{id}/chargeback")
    public ResponseEntity<Map<String, Object>> chargeback(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.sendChargeback(id, b != null ? b.acquirerCaseRef() : null, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/represent")
    public ResponseEntity<Map<String, Object>> represent(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.represent(id, b != null ? b.note() : null, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable Long id, @RequestBody NoteBody b) {
        Dispute.Outcome outcome;
        try { outcome = Dispute.Outcome.valueOf(String.valueOf(b.outcome()).toUpperCase()); }
        catch (IllegalArgumentException e) { throw new BusinessException("DISPUTE_INVALID_OUTCOME", "outcome must be CUSTOMER or MERCHANT", HttpStatus.BAD_REQUEST); }
        return ResponseEntity.ok(view(service.resolve(id, outcome, b.note(), b.by())));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.withdraw(id, b != null ? b.note() : null, b != null ? b.by() : null)));
    }

    /** The expediente. */
    @GetMapping("/{id}/file")
    public ResponseEntity<Map<String, Object>> file(@PathVariable Long id) {
        return ResponseEntity.ok(service.dossier(id));
    }

    static Map<String, Object> view(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("status", d.getStatus().name());
        m.put("cardId", d.getCard().getId());
        m.put("approvalCode", d.getApprovalCode());
        m.put("amount", d.getAmount());
        m.put("currency", d.getCurrency());
        m.put("reasonCode", d.getReason().getCode());
        m.put("reason", d.getReason().getDescription());
        m.put("description", d.getDescription());
        m.put("provisionalCredit", d.isProvisionalCredit());
        m.put("creditRef", d.getCreditRef());
        m.put("creditReversalRef", d.getCreditReversalRef());
        m.put("openedBy", d.getOpenedBy());
        m.put("openedAt", d.getCreatedAt());
        m.put("transactionDate", d.getTransactionDate());
        m.put("chargebackDeadline", d.getChargebackDeadline());
        m.put("representmentDeadline", d.getRepresentmentDeadline());
        m.put("resolveBy", d.getResolveBy());
        m.put("nextDeadline", d.nextDeadline());
        m.put("deadlineBreached", d.isDeadlineBreached());
        m.put("acquirerCaseRef", d.getAcquirerCaseRef());
        m.put("outcome", d.getOutcome() != null ? d.getOutcome().name() : null);
        m.put("resolutionNote", d.getResolutionNote());
        m.put("resolvedAt", d.getResolvedAt());
        return m;
    }

    static Map<String, Object> view(DisputeEvidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("filename", e.getFilename());
        m.put("contentType", e.getContentType());
        m.put("size", e.getSize());
        m.put("sha256", e.getSha256());
        m.put("description", e.getDescription());
        m.put("addedBy", e.getAddedBy());
        m.put("addedAt", e.getCreatedAt());
        return m;
    }

    static Map<String, Object> view(DisputeReason r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("description", r.getDescription());
        m.put("network", r.getNetwork());
        m.put("chargebackDays", r.getChargebackDays());
        m.put("representmentDays", r.getRepresentmentDays());
        m.put("resolveDays", r.getResolveDays());
        m.put("requiresEvidence", r.isRequiresEvidence());
        return m;
    }
}
