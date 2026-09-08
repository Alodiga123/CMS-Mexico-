package bank.cardissuing.plastics.infrastructure;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.plastics.application.PlasticService;
import bank.cardissuing.plastics.domain.Plastic;
import bank.cardissuing.plastics.domain.PlasticBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plastics: request, batch for the bureau, track to activation, replace, renew. */
@RestController
@RequestMapping("/api/plastics")
@RequiredArgsConstructor
public class PlasticController {

    private final PlasticService service;

    public record RequestBody_(Long cardId, String reason, String deliveryAddress, Boolean pinMailer, String by) { }
    public record ShipBody(String carrier, String trackingNumber, String by) { }
    public record NoteBody(String note, String by) { }
    public record ReplaceBody(String reason, String deliveryAddress, Boolean pinMailer, String by) { }
    public record BatchBody(String manufacturer, String by) { }
    public record ProducedBody(List<Long> failedPlasticIds, String note, String by) { }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(@RequestBody RequestBody_ b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.requestForCard(b.cardId(), reason(b.reason()),
                b.deliveryAddress(), Boolean.TRUE.equals(b.pinMailer()), b.by())));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) Long cardId,
                                                          @RequestParam(required = false) String status) {
        List<Plastic> list = cardId != null ? service.byCard(cardId)
                : service.byStatus(status != null ? Plastic.Status.valueOf(status.toUpperCase()) : null);
        return ResponseEntity.ok(list.stream().map(PlasticController::view).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) { return ResponseEntity.ok(view(service.get(id))); }

    @PostMapping("/{id}/ship")
    public ResponseEntity<Map<String, Object>> ship(@PathVariable Long id, @RequestBody ShipBody b) {
        return ResponseEntity.ok(view(service.ship(id, b.carrier(), b.trackingNumber(), b.by())));
    }

    @PostMapping("/{id}/deliver")
    public ResponseEntity<Map<String, Object>> deliver(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.deliver(id, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.activate(id, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<Map<String, Object>> returned(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.returned(id, b != null ? b.note() : null, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/destroy")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.destroy(id, b != null ? b.note() : null, b != null ? b.by() : null)));
    }

    @PostMapping("/{id}/replace")
    public ResponseEntity<Map<String, Object>> replace(@PathVariable Long id, @RequestBody ReplaceBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.replace(id, reason(b.reason()),
                b.deliveryAddress(), Boolean.TRUE.equals(b.pinMailer()), b.by())));
    }

    @PostMapping("/renewals")
    public ResponseEntity<List<Map<String, Object>>> renewals(@RequestParam(required = false) Integer withinDays,
                                                              @RequestParam(required = false) String by) {
        return ResponseEntity.ok(service.renewals(withinDays, by).stream().map(PlasticController::view).toList());
    }

    // ---- batches ----

    @PostMapping("/batches")
    public ResponseEntity<Map<String, Object>> build(@RequestBody(required = false) BatchBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.buildBatch(b != null ? b.manufacturer() : null, b != null ? b.by() : null)));
    }

    @GetMapping("/batches")
    public ResponseEntity<List<Map<String, Object>>> batches() {
        return ResponseEntity.ok(service.batches().stream().map(PlasticController::view).toList());
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<Map<String, Object>> batch(@PathVariable Long id) {
        Map<String, Object> m = view(service.batch(id));
        m.put("plastics", service.inBatch(id).stream().map(PlasticController::view).toList());
        return ResponseEntity.ok(m);
    }

    /** The encrypted file as the bureau receives it. */
    @GetMapping("/batches/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        PlasticBatch b = service.batch(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + b.getBatchNumber() + ".perso.enc\"")
                .header("X-Perso-Algorithm", b.getAlgorithm())
                .header("X-Perso-IV", b.getIv())
                .header("X-Perso-SHA256-Plain", b.getSha256Plain())
                .body(b.getEncryptedFile());
    }

    @GetMapping(value = "/batches/{id}/file/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> preview(@PathVariable Long id) { return ResponseEntity.ok(service.preview(id)); }

    @PostMapping("/batches/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long id, @RequestBody(required = false) NoteBody b) {
        return ResponseEntity.ok(view(service.send(id, b != null ? b.by() : null)));
    }

    /** The bureau's report: which plastic ids it could not produce. */
    @PostMapping("/batches/{id}/produced")
    public ResponseEntity<Map<String, Object>> produced(@PathVariable Long id, @RequestBody(required = false) ProducedBody b) {
        return ResponseEntity.ok(view(service.produced(id, b != null ? b.failedPlasticIds() : null, b != null ? b.note() : null, b != null ? b.by() : null)));
    }

    private static Plastic.Reason reason(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Plastic.Reason.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new BusinessException("PLASTIC_INVALID_REASON", "Unknown reason '" + s + "'", HttpStatus.BAD_REQUEST); }
    }

    static Map<String, Object> view(Plastic p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("cardId", p.getCard().getId());
        m.put("sequence", p.getSequence());
        m.put("status", p.getStatus().name());
        m.put("reason", p.getReason().name());
        m.put("embossedName", p.getEmbossedName());
        m.put("expiry", p.getExpiry());
        m.put("chipProfile", p.getChipProfile());
        m.put("pinMailer", p.isPinMailer());
        m.put("deliveryAddress", p.getDeliveryAddress());
        m.put("batchId", p.getBatch() != null ? p.getBatch().getId() : null);
        m.put("batchNumber", p.getBatch() != null ? p.getBatch().getBatchNumber() : null);
        m.put("carrier", p.getCarrier());
        m.put("trackingNumber", p.getTrackingNumber());
        m.put("note", p.getNote());
        m.put("requestedBy", p.getRequestedBy());
        m.put("requestedAt", p.getCreatedAt());
        m.put("batchedAt", p.getBatchedAt());
        m.put("sentAt", p.getSentAt());
        m.put("producedAt", p.getProducedAt());
        m.put("shippedAt", p.getShippedAt());
        m.put("deliveredAt", p.getDeliveredAt());
        m.put("activatedAt", p.getActivatedAt());
        m.put("closedAt", p.getClosedAt());
        return m;
    }

    static Map<String, Object> view(PlasticBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("batchNumber", b.getBatchNumber());
        m.put("manufacturer", b.getManufacturer());
        m.put("status", b.getStatus().name());
        m.put("recordCount", b.getRecordCount());
        m.put("failedCount", b.getFailedCount());
        m.put("sha256Plain", b.getSha256Plain());
        m.put("algorithm", b.getAlgorithm());
        m.put("encryptedSize", b.getEncryptedFile() != null ? b.getEncryptedFile().length : 0);
        m.put("builtBy", b.getBuiltBy());
        m.put("builtAt", b.getCreatedAt());
        m.put("sentAt", b.getSentAt());
        m.put("producedAt", b.getProducedAt());
        return m;
    }
}
