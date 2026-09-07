package bank.cardissuing.transaction.infrastructure;

import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.transaction.application.AuthorizationService;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/authorization")
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    /** 0100 in, 0110 out. Always 200: the decision is in the body. */
    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @Valid @RequestBody AuthorizationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(authorizationService.authorize(request, idempotencyKey));
    }

    @PostMapping("/{approvalCode}/capture")
    public ResponseEntity<Map<String, Object>> capture(@PathVariable String approvalCode,
                                                       @RequestBody(required = false) Map<String, BigDecimal> body) {
        BigDecimal amount = body != null ? body.get("amount") : null;
        return ResponseEntity.ok(view(authorizationService.capture(approvalCode, amount)));
    }

    @PostMapping("/{approvalCode}/reverse")
    public ResponseEntity<Map<String, Object>> reverse(@PathVariable String approvalCode) {
        return ResponseEntity.ok(view(authorizationService.reverse(approvalCode)));
    }

    @GetMapping("/{approvalCode}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String approvalCode) {
        return ResponseEntity.ok(view(authorizationService.get(approvalCode)));
    }

    private static Map<String, Object> view(AuthorizationHold h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approvalCode", h.getApprovalCode());
        m.put("cardId", h.getCard().getId());
        m.put("status", h.getStatus().name());
        m.put("amount", h.getAmount());
        m.put("capturedAmount", h.getCapturedAmount());
        m.put("merchantName", h.getMerchantName());
        m.put("merchantId", h.getMerchantId());
        m.put("transactionType", h.getTransactionType());
        m.put("externalRef", h.getExternalRef());
        m.put("createdAt", h.getCreatedAt());
        m.put("expiresAt", h.getExpiresAt());
        m.put("updatedAt", h.getUpdatedAt() != null ? h.getUpdatedAt() : LocalDateTime.now());
        return m;
    }
}
