package bank.cardissuing.thirdparty.infrastructure;

import bank.cardissuing.common.api.PageResponse;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.thirdparty.messaging.MessagingProvider;
import bank.cardissuing.thirdparty.messaging.MessagingService;
import bank.cardissuing.thirdparty.messaging.OutboundMessage;
import bank.cardissuing.thirdparty.messaging.SimulatedMessagingProvider;
import bank.cardissuing.thirdparty.registry.ThirdPartyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The third parties: registry, contracts, and the messaging outbox. */
@RestController
@RequestMapping("/api/thirdparties")
@RequiredArgsConstructor
public class ThirdPartyController {

    private final ThirdPartyRegistry registry;
    private final MessagingService messaging;

    public record TestMessage(String channel, String to, String text, String by) { }
    public record SimulatorBody(Boolean down) { }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() { return ResponseEntity.ok(registry.list()); }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable String key) { return ResponseEntity.ok(registry.one(key, false)); }

    /** A live check, the expensive calls included. */
    @PostMapping("/{key}/check")
    public ResponseEntity<Map<String, Object>> check(@PathVariable String key) { return ResponseEntity.ok(registry.one(key, true)); }

    @PutMapping("/{key}/contract")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable String key, @RequestBody ThirdPartyRegistry.ContractUpdate u) {
        return ResponseEntity.ok(ThirdPartyRegistry.view(registry.update(key, u)));
    }

    // ---- messaging ----

    @GetMapping("/messaging/status")
    public ResponseEntity<Map<String, Object>> messagingStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        MessagingProvider p = messaging.provider();
        MessagingProvider.Health h = p.health();
        m.put("mode", p.mode()); m.put("up", h.up()); m.put("detail", h.detail()); m.put("queued", messaging.queued());
        if (p instanceof SimulatedMessagingProvider sim) m.put("simulatorDown", sim.isDown());
        return ResponseEntity.ok(m);
    }

    /** Switch the simulated provider off and on to rehearse an outage. */
    @PostMapping("/messaging/simulator")
    public ResponseEntity<Map<String, Object>> simulator(@RequestBody SimulatorBody b) {
        if (!(messaging.provider() instanceof SimulatedMessagingProvider sim)) throw new BusinessException("MESSAGING_NOT_SIMULATED", "The messaging provider is not the simulator", HttpStatus.CONFLICT);
        sim.setDown(Boolean.TRUE.equals(b.down()));
        return messagingStatus();
    }

    @GetMapping("/messages")
    public ResponseEntity<PageResponse<Map<String, Object>>> messages(@RequestParam(required = false) String status, @RequestParam(required = false) Long cardId,
                                                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        OutboundMessage.Status s = status == null || status.isBlank() ? null : OutboundMessage.Status.valueOf(status.toUpperCase());
        return ResponseEntity.ok(PageResponse.of(messaging.page(s, cardId, PageResponse.pageable(page, size)), ThirdPartyController::view));
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<Map<String, Object>> message(@PathVariable Long id) { return ResponseEntity.ok(view(messaging.get(id))); }

    @PostMapping("/messages/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable Long id) { return ResponseEntity.ok(view(messaging.retry(id))); }

    @PostMapping("/messages/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody TestMessage b) {
        MessagingProvider.Channel ch = b.channel() == null || b.channel().isBlank() ? null : MessagingProvider.Channel.valueOf(b.channel().toUpperCase());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(messaging.adHoc(ch, b.to(), b.text(), "TEST")));
    }

    static Map<String, Object> view(OutboundMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId()); v.put("channel", m.getChannel().name()); v.put("recipient", mask(m.getRecipient()));
        v.put("cardId", m.getCardId()); v.put("customerId", m.getCustomerId()); v.put("template", m.getTemplate()); v.put("text", m.getText());
        v.put("status", m.getStatus().name()); v.put("providerMode", m.getProviderMode()); v.put("providerRef", m.getProviderRef());
        v.put("attempts", m.getAttempts()); v.put("lastError", m.getLastError()); v.put("businessRef", m.getBusinessRef());
        v.put("createdAt", m.getCreatedAt()); v.put("sentAt", m.getSentAt());
        return v;
    }

    private static String mask(String to) {
        if (to == null || to.length() < 4) return to == null ? null : "****";
        return "*".repeat(Math.max(0, to.length() - 4)) + to.substring(to.length() - 4);
    }
}
