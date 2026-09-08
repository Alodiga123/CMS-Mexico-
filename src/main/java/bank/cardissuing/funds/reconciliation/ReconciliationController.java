package bank.cardissuing.funds.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The board of what the CMS and the core disagree about. */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationItemRepository repo;

    /** Reconcile every linked core account, or just one. */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationService.RunResult> run(@RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(accountId != null && !accountId.isBlank()
                ? service.reconcileAccount(accountId)
                : service.reconcileAll());
    }

    /** Open by default; ?status=RESOLVED or ?status=ALL for the rest. */
    @GetMapping("/items")
    public ResponseEntity<List<Map<String, Object>>> items(@RequestParam(defaultValue = "OPEN") String status) {
        List<ReconciliationItem> list = switch (status.toUpperCase()) {
            case "ALL" -> repo.findAllByOrderByCreatedAtDesc();
            case "RESOLVED" -> repo.findByStatusOrderByCreatedAtDesc(ReconciliationItem.Status.RESOLVED);
            default -> repo.findByStatusOrderByCreatedAtDesc(ReconciliationItem.Status.OPEN);
        };
        return ResponseEntity.ok(list.stream().map(ReconciliationController::view).toList());
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> account(@PathVariable String accountId) {
        ReconciliationService.AccountSummary s = service.summary(accountId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", s.accountId());
        m.put("cardIds", s.cardIds());
        m.put("cmsHeld", s.cmsHeld());
        m.put("coreOnHold", s.coreOnHold());
        m.put("coreAvailable", s.coreAvailable());
        m.put("holdTotalsAgree", s.cmsHeld().compareTo(s.coreOnHold()) == 0);
        m.put("openItems", s.openItems().stream().map(ReconciliationController::view).toList());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/items/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(view(service.resolve(id, body != null ? body.get("note") : null)));
    }

    static Map<String, Object> view(ReconciliationItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("type", i.getType().name());
        m.put("status", i.getStatus().name());
        m.put("accountId", i.getAccountId());
        m.put("cardId", i.getCardId());
        m.put("approvalCode", i.getApprovalCode());
        m.put("coreRef", i.getCoreRef());
        m.put("cmsAmount", i.getCmsAmount());
        m.put("coreAmount", i.getCoreAmount());
        m.put("detail", i.getDetail());
        m.put("detectedAt", i.getCreatedAt());
        m.put("lastSeenAt", i.getLastSeenAt());
        m.put("resolvedAt", i.getResolvedAt());
        m.put("resolution", i.getResolution());
        return m;
    }
}
