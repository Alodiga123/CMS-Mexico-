package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.application.CardControlsService;
import bank.cardissuing.card.application.CardControlsUpdate;
import bank.cardissuing.card.domain.CardControls;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a card may do: channels, international, per-card limits. The console's "reglas". */
@RestController
@RequestMapping("/api/cards/{cardId}/controls")
@RequiredArgsConstructor
public class CardControlsController {

    private final CardControlsService service;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long cardId) {
        return ResponseEntity.ok(view(cardId, service.forCardId(cardId)));
    }

    /** Partial: only the fields present in the body change. */
    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long cardId, @RequestBody CardControlsUpdate body) {
        return ResponseEntity.ok(view(cardId, service.update(cardId, body)));
    }

    private static Map<String, Object> view(Long cardId, CardControls c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardId", cardId);
        m.put("posEnabled", c.isPosEnabled());
        m.put("atmEnabled", c.isAtmEnabled());
        m.put("ecommerceEnabled", c.isEcommerceEnabled());
        m.put("contactlessEnabled", c.isContactlessEnabled());
        m.put("internationalEnabled", c.isInternationalEnabled());
        m.put("travelNoticeUntil", c.getTravelNoticeUntil());
        m.put("dailyLimit", c.getDailyLimit());
        m.put("weeklyLimit", c.getWeeklyLimit());
        m.put("monthlyLimit", c.getMonthlyLimit());
        m.put("perTransactionMax", c.getPerTransactionMax());
        m.put("persisted", c.getId() != null);
        return m;
    }
}
