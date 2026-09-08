package bank.cardissuing.fraud.infrastructure;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.fraud.application.FraudService;
import bank.cardissuing.fraud.domain.AuthorizationAttempt;
import bank.cardissuing.fraud.domain.BlockedEntity;
import bank.cardissuing.fraud.domain.FraudAlert;
import bank.cardissuing.fraud.domain.StepUpChallenge;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The analyst's desk: what the engine decided, why, and the levers to act on it. */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudController {

    private final FraudService service;

    public record ReviewBody(String action, String note, String by) { }
    public record BlockBody(String type, String value, String reason, String by) { }
    public record VerifyBody(String otp) { }

    /** Every decision on a card, newest first. */
    @GetMapping("/attempts")
    public ResponseEntity<List<Map<String, Object>>> attempts(@RequestParam Long cardId) {
        return ResponseEntity.ok(service.history(cardId).stream().map(FraudController::view).toList());
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> alerts(@RequestParam(defaultValue = "OPEN") String status) {
        return ResponseEntity.ok(service.alerts(status).stream().map(FraudController::view).toList());
    }

    @PostMapping("/alerts/{id}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable Long id, @RequestBody ReviewBody b) {
        return ResponseEntity.ok(view(service.review(id, b.action(), b.note(), b.by())));
    }

    @GetMapping("/blocklist")
    public ResponseEntity<List<Map<String, Object>>> blocklist() {
        return ResponseEntity.ok(service.blocklist().stream().map(FraudController::view).toList());
    }

    @PostMapping("/blocklist")
    public ResponseEntity<Map<String, Object>> block(@RequestBody BlockBody b) {
        BlockedEntity.Type type;
        try { type = BlockedEntity.Type.valueOf(String.valueOf(b.type()).toUpperCase()); }
        catch (IllegalArgumentException e) { throw new BusinessException("BLOCKLIST_INVALID_TYPE", "type must be MERCHANT_ID, COUNTRY or CARD", HttpStatus.BAD_REQUEST); }
        if (b.value() == null || b.value().isBlank()) throw new BusinessException("BLOCKLIST_INVALID_VALUE", "value is required", HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.block(type, b.value(), b.reason(), b.by())));
    }

    @DeleteMapping("/blocklist/{id}")
    public ResponseEntity<Void> unblock(@PathVariable Long id) {
        service.unblock(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/challenges/{token}")
    public ResponseEntity<Map<String, Object>> challenge(@PathVariable String token) {
        return ResponseEntity.ok(view(service.challenge(token)));
    }

    /** The cardholder types the code; on success the merchant retries the authorization with stepUpToken. */
    @PostMapping("/challenges/{token}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String token, @RequestBody VerifyBody b) {
        return ResponseEntity.ok(view(service.verify(token, b.otp())));
    }

    static Map<String, Object> view(AuthorizationAttempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("at", a.getCreatedAt());
        m.put("amount", a.getAmount());
        m.put("merchantName", a.getMerchantName());
        m.put("merchantId", a.getMerchantId());
        m.put("channel", a.getChannel());
        m.put("countryCode", a.getCountryCode());
        m.put("responseCode", a.getResponseCode());
        m.put("approved", a.isApproved());
        m.put("approvalCode", a.getApprovalCode());
        m.put("riskScore", a.getRiskScore());
        m.put("riskReasons", a.getRiskReasons() == null || a.getRiskReasons().isBlank() ? List.of() : List.of(a.getRiskReasons().split(",")));
        m.put("stepUp", a.isStepUp());
        return m;
    }

    static Map<String, Object> view(FraudAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("type", a.getType().name());
        m.put("status", a.getStatus().name());
        m.put("at", a.getCreatedAt());
        m.put("cardId", a.getCard() != null ? a.getCard().getId() : null);
        m.put("merchantId", a.getMerchantId());
        m.put("merchantName", a.getMerchantName());
        m.put("riskScore", a.getRiskScore());
        m.put("reasons", a.getReasons());
        m.put("amount", a.getAmount());
        m.put("analystNote", a.getAnalystNote());
        m.put("actionTaken", a.getActionTaken());
        m.put("reviewedBy", a.getReviewedBy());
        m.put("reviewedAt", a.getReviewedAt());
        return m;
    }

    static Map<String, Object> view(BlockedEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getType().name());
        m.put("value", e.getValue());
        m.put("source", e.getSource().name());
        m.put("reason", e.getReason());
        m.put("addedBy", e.getAddedBy());
        m.put("addedAt", e.getCreatedAt());
        m.put("active", e.isActive());
        return m;
    }

    static Map<String, Object> view(StepUpChallenge c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", c.getToken());
        m.put("status", c.getStatus().name());
        m.put("cardId", c.getCard().getId());
        m.put("amount", c.getAmount());
        m.put("merchantId", c.getMerchantId());
        m.put("channel", c.getChannel());
        m.put("expiresAt", c.getExpiresAt());
        m.put("attempts", c.getAttempts());
        return m;
    }
}
