package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.fraud.guild.application.GuildClient;
import bank.cardissuing.fraud.guild.application.GuildService;
import bank.cardissuing.fraud.guild.domain.GuildAlert;
import bank.cardissuing.fraud.guild.domain.GuildVerification;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The industry antifraud connection as the console sees it: status, alerts both ways,
 * manual alerts, verification against the guild's list, and (simulated mode only) the
 * knobs the tests use to play the guild's part.
 */
@RestController
@RequestMapping("/api/guild")
@RequiredArgsConstructor
public class GuildController {

    private final GuildService service;
    private final GuildClient client;
    private final CardRepository cards;

    public record RaiseBody(String type, Long cardId, String merchantId, String merchantName, String description, BigDecimal amount, String by) { }
    public record CloseBody(String resolution, String by) { }
    public record ListCardBody(String bin, String last4, String reason) { }
    public record ListMerchantBody(String merchantId, String reason) { }
    public record InboundBody(String type, String bin, String last4, String merchantId, String merchantName, String description) { }
    public record DownBody(boolean down) { }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() { return ResponseEntity.ok(service.status()); }

    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> alerts(@RequestParam(required = false) String direction,
                                                            @RequestParam(required = false) String status) {
        GuildAlert.Direction d = direction != null ? GuildAlert.Direction.valueOf(direction.toUpperCase()) : null;
        GuildAlert.Status s = status != null ? GuildAlert.Status.valueOf(status.toUpperCase()) : null;
        return ResponseEntity.ok(service.list(d, s).stream().map(GuildController::view).toList());
    }

    @GetMapping("/alerts/{id}")
    public ResponseEntity<Map<String, Object>> alert(@PathVariable Long id) { return ResponseEntity.ok(view(service.get(id))); }

    @PostMapping("/alerts")
    public ResponseEntity<Map<String, Object>> raise(@RequestBody RaiseBody b) {
        GuildAlert.Type type;
        try { type = GuildAlert.Type.valueOf(b.type() == null ? "" : b.type().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new BusinessException("GUILD_BAD_TYPE", "Unknown type '" + b.type() + "'", HttpStatus.BAD_REQUEST); }
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.raise(type, b.cardId(), b.merchantId(), b.merchantName(), b.description(), b.amount(), b.by())));
    }

    @PostMapping("/alerts/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long id) { return ResponseEntity.ok(view(service.sendNow(id))); }

    @PostMapping("/alerts/{id}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable Long id, @RequestBody CloseBody b) {
        return ResponseEntity.ok(view(service.close(id, b.resolution(), b.by())));
    }

    @PostMapping("/outbox/flush")
    public ResponseEntity<Map<String, Object>> flush() { return ResponseEntity.ok(Map.of("sent", service.flushOutbox())); }

    @PostMapping("/inbound/poll")
    public ResponseEntity<Map<String, Object>> poll() { return ResponseEntity.ok(Map.of("received", service.pollInbound())); }

    @PostMapping("/deadlines/run")
    public ResponseEntity<Map<String, Object>> deadlines() { return ResponseEntity.ok(Map.of("expired", service.expireOverdue())); }

    @PostMapping("/verify/card/{cardId}")
    public ResponseEntity<Map<String, Object>> verifyCard(@PathVariable Long cardId) {
        Card c = cards.findById(cardId).orElseThrow(() -> new BusinessException("CARD_NOT_FOUND", "Card " + cardId + " not found", HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(view(service.verifyCard(c)));
    }

    @PostMapping("/verify/merchant/{merchantId}")
    public ResponseEntity<Map<String, Object>> verifyMerchant(@PathVariable String merchantId) {
        return ResponseEntity.ok(view(service.verifyMerchant(merchantId)));
    }

    @GetMapping("/verifications")
    public ResponseEntity<List<Map<String, Object>>> verifications() {
        return ResponseEntity.ok(service.verifications().stream().map(GuildController::view).toList());
    }

    // ---- simulator (only when the guild is the in-memory one) ----

    @PostMapping("/simulator/listed-cards")
    public ResponseEntity<Map<String, Object>> simListCard(@RequestBody ListCardBody b) {
        sim().listCard(b.bin(), b.last4(), b.reason());
        return ResponseEntity.ok(Map.of("listed", b.bin() + "*" + b.last4()));
    }

    @PostMapping("/simulator/listed-merchants")
    public ResponseEntity<Map<String, Object>> simListMerchant(@RequestBody ListMerchantBody b) {
        sim().listMerchant(b.merchantId(), b.reason());
        return ResponseEntity.ok(Map.of("listed", b.merchantId()));
    }

    @PostMapping("/simulator/inbound")
    public ResponseEntity<GuildClient.Inbound> simInbound(@RequestBody InboundBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sim().pushInbound(b.type(), b.bin(), b.last4(), b.merchantId(), b.merchantName(), b.description()));
    }

    @PostMapping("/simulator/down")
    public ResponseEntity<Map<String, Object>> simDown(@RequestBody DownBody b) {
        sim().setDown(b.down());
        return ResponseEntity.ok(Map.of("down", sim().isDown()));
    }

    @GetMapping("/simulator/sent")
    public ResponseEntity<List<GuildClient.Outbound>> simSent() { return ResponseEntity.ok(sim().sent()); }

    private SimulatedGuildClient sim() {
        if (client instanceof SimulatedGuildClient s) return s;
        throw new BusinessException("GUILD_SIMULATOR_UNAVAILABLE", "The guild connection is " + client.mode() + ", not simulated", HttpStatus.CONFLICT);
    }

    static Map<String, Object> view(GuildAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("direction", a.getDirection().name());
        m.put("type", a.getType().name());
        m.put("status", a.getStatus().name());
        m.put("cardId", a.getCardId());
        m.put("bin", a.getBin());
        m.put("last4", a.getLast4());
        m.put("merchantId", a.getMerchantId());
        m.put("merchantName", a.getMerchantName());
        m.put("amount", a.getAmount());
        m.put("description", a.getDescription());
        m.put("guildFolio", a.getGuildFolio());
        m.put("sourceRef", a.getSourceRef());
        m.put("raisedBy", a.getRaisedBy());
        m.put("attempts", a.getAttempts());
        m.put("lastError", a.getLastError());
        m.put("createdAt", a.getCreatedAt());
        m.put("sentAt", a.getSentAt());
        m.put("receivedAt", a.getReceivedAt());
        m.put("respondBy", a.getRespondBy());
        m.put("closedAt", a.getClosedAt());
        m.put("closedBy", a.getClosedBy());
        m.put("resolution", a.getResolution());
        m.put("autoAction", a.getAutoAction());
        m.put("assumedLoss", a.isAssumedLoss());
        return m;
    }

    static Map<String, Object> view(GuildVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("subjectType", v.getSubjectType().name());
        m.put("subject", v.getSubject());
        m.put("listed", v.isListed());
        m.put("folio", v.getFolio());
        m.put("reason", v.getReason());
        m.put("degraded", v.isDegraded());
        m.put("checkedAt", v.getCheckedAt());
        m.put("validUntil", v.getValidUntil());
        return m;
    }
}
