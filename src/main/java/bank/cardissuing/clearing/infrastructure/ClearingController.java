package bank.cardissuing.clearing.infrastructure;

import bank.cardissuing.clearing.application.ClearingService;
import bank.cardissuing.clearing.application.NetworkFileSimulator;
import bank.cardissuing.clearing.application.SettlementService;
import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.ClearingRecord;
import bank.cardissuing.clearing.domain.SettlementCycle;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Clearing files in, settlement positions out. */
@RestController
@RequestMapping("/api/clearing")
@RequiredArgsConstructor
public class ClearingController {

    private final ClearingService clearing;
    private final SettlementService settlement;
    private final NetworkFileSimulator simulator;

    public record FileBody(String fileName, String content, String by) { }
    public record SimulateBody(String network, String cycleDate, String anomalies, Long cardId, Boolean ingest, String by) { }
    public record PaidBody(String paymentRef, String by) { }
    public record ByBody(String by) { }

    /** Load a clearing file (CMS-CLR layout) and process it. */
    @PostMapping("/files")
    public ResponseEntity<Map<String, Object>> load(@RequestBody FileBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(clearing.ingest(b.fileName(), b.content(), b.by())));
    }

    /** The network simulator: the file an acquirer would send for what we approved, with the anomalies asked for. */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulate(@RequestBody SimulateBody b) {
        Set<String> anomalies = b.anomalies() == null ? Set.of() : new HashSet<>(Arrays.asList(b.anomalies().toLowerCase().split("[,\\s]+")));
        String file = simulator.build(SettlementService.network(b.network() == null ? "VISA" : b.network()), SettlementService.cycleDate(b.cycleDate()), anomalies, b.cardId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", file);
        m.put("lines", file.split("\n").length);
        if (Boolean.TRUE.equals(b.ingest())) m.put("batch", view(clearing.ingest("SIM-" + b.network() + ".clr", file, b.by())));
        return ResponseEntity.ok(m);
    }

    @GetMapping("/batches")
    public ResponseEntity<List<Map<String, Object>>> batches() { return ResponseEntity.ok(clearing.recent().stream().map(ClearingController::view).toList()); }

    @GetMapping("/batches/{id}")
    public ResponseEntity<Map<String, Object>> batch(@PathVariable Long id) { return ResponseEntity.ok(view(clearing.batch(id))); }

    @GetMapping("/batches/{id}/records")
    public ResponseEntity<List<Map<String, Object>>> records(@PathVariable Long id, @RequestParam(required = false) String outcome) {
        ClearingRecord.Outcome o = outcome == null || outcome.isBlank() ? null : ClearingRecord.Outcome.valueOf(outcome.toUpperCase());
        return ResponseEntity.ok(clearing.recordsOf(id, o).stream().map(ClearingController::view).toList());
    }

    @GetMapping("/batches/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        ClearingBatch b = clearing.batch(id);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header("Content-Disposition", "attachment; filename=\"" + b.getFileName() + "\"")
                .header("X-File-SHA256", b.getSha256()).body(b.getContent().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/exceptions")
    public ResponseEntity<List<Map<String, Object>>> exceptions() { return ResponseEntity.ok(clearing.exceptions().stream().map(ClearingController::view).toList()); }

    // ---- settlement ----

    @GetMapping("/settlement/cycles")
    public ResponseEntity<List<Map<String, Object>>> cycles() { return ResponseEntity.ok(settlement.recent().stream().map(ClearingController::view).toList()); }

    @GetMapping("/settlement/cycles/{id}")
    public ResponseEntity<Map<String, Object>> cycle(@PathVariable Long id) { return ResponseEntity.ok(view(settlement.get(id))); }

    @PostMapping("/settlement/cycles/{id}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable Long id, @RequestBody(required = false) ByBody b) {
        return ResponseEntity.ok(view(settlement.close(id, b != null ? b.by() : null)));
    }

    @PostMapping("/settlement/cycles/{id}/paid")
    public ResponseEntity<Map<String, Object>> paid(@PathVariable Long id, @RequestBody PaidBody b) {
        return ResponseEntity.ok(view(settlement.markPaid(id, b.paymentRef(), b.by())));
    }

    @GetMapping("/settlement/cycles/{id}/file")
    public ResponseEntity<byte[]> settlementFile(@PathVariable Long id) {
        SettlementCycle c = settlement.get(id);
        if (c.getSettlementFile() == null) return ResponseEntity.status(HttpStatus.CONFLICT).body("cycle not closed".getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"settlement_" + c.getNetwork().name().toLowerCase() + "_" + c.getCycleDate() + ".csv\"")
                .header("X-File-SHA256", c.getSha256()).body(c.getSettlementFile().getBytes(StandardCharsets.UTF_8));
    }

    // ---- views ----

    static Map<String, Object> view(ClearingBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId()); m.put("network", b.getNetwork().name()); m.put("fileName", b.getFileName()); m.put("fileId", b.getFileId());
        m.put("cycleDate", b.getCycleDate()); m.put("status", b.getStatus().name()); m.put("sha256", b.getSha256());
        m.put("recordCount", b.getRecordCount()); m.put("trailerCount", b.getTrailerCount()); m.put("trailerAmount", b.getTrailerAmount());
        m.put("presentmentsCount", b.getPresentmentsCount()); m.put("presentmentsAmount", b.getPresentmentsAmount());
        m.put("reversalsAmount", b.getReversalsAmount()); m.put("chargebacksAmount", b.getChargebacksAmount()); m.put("interchangeAmount", b.getInterchangeAmount()); m.put("feesAmount", b.getFeesAmount());
        m.put("matchedCount", b.getMatchedCount()); m.put("exceptionCount", b.getExceptionCount());
        m.put("loadedBy", b.getLoadedBy()); m.put("loadedAt", b.getCreatedAt()); m.put("processedAt", b.getProcessedAt());
        m.put("settlementCycleId", b.getSettlementCycleId()); m.put("error", b.getError());
        return m;
    }

    static Map<String, Object> view(ClearingRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("batchId", r.getBatch() != null ? r.getBatch().getId() : null); m.put("lineNo", r.getLineNo()); m.put("type", r.getType().name()); m.put("pan", r.getPanMasked()); m.put("cardId", r.getCardId());
        m.put("rrn", r.getRrn()); m.put("stan", r.getStan()); m.put("approvalId", r.getApprovalId()); m.put("approvalCode", r.getApprovalCode());
        m.put("amount", r.getAmount()); m.put("currency", r.getCurrency()); m.put("interchangeFee", r.getInterchangeFee()); m.put("mcc", r.getMcc());
        m.put("merchantId", r.getMerchantId()); m.put("merchantName", r.getMerchantName()); m.put("transactionDate", r.getTransactionDate());
        m.put("reasonCode", r.getReasonCode()); m.put("outcome", r.getOutcome() != null ? r.getOutcome().name() : null); m.put("detail", r.getDetail());
        return m;
    }

    static Map<String, Object> view(SettlementCycle c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("network", c.getNetwork().name()); m.put("cycleDate", c.getCycleDate()); m.put("status", c.getStatus().name());
        m.put("batchCount", c.getBatchCount()); m.put("presentmentsCount", c.getPresentmentsCount()); m.put("presentmentsAmount", c.getPresentmentsAmount());
        m.put("reversalsAmount", c.getReversalsAmount()); m.put("chargebacksAmount", c.getChargebacksAmount()); m.put("interchangeAmount", c.getInterchangeAmount()); m.put("feesAmount", c.getFeesAmount());
        m.put("netPosition", c.getNetPosition()); m.put("direction", c.getNetPosition().signum() >= 0 ? "ISSUER_PAYS" : "ISSUER_RECEIVES");
        m.put("exceptionCount", c.getExceptionCount()); m.put("currency", c.getCurrency()); m.put("sha256", c.getSha256());
        m.put("closedAt", c.getClosedAt()); m.put("closedBy", c.getClosedBy()); m.put("paidAt", c.getPaidAt()); m.put("paymentRef", c.getPaymentRef());
        return m;
    }
}
