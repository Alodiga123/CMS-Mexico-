package bank.cardissuing.reports.infrastructure;

import bank.cardissuing.reports.application.ReportCatalog;
import bank.cardissuing.reports.application.ReportService;
import bank.cardissuing.reports.application.ReportService.Options;
import bank.cardissuing.reports.application.ReportService.Table;
import bank.cardissuing.reports.domain.ReportRun;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports: the catalogue, a live preview of any report, sealed runs kept as evidence,
 * the CSV download and a way to verify a stored run has not been touched.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService service;

    public record RunBody(String by) { }

    @GetMapping
    public ResponseEntity<List<ReportCatalog.Definition>> catalog() { return ResponseEntity.ok(ReportCatalog.ALL); }

    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> runs(@RequestParam(required = false) String code) {
        return ResponseEntity.ok(service.recent(code).stream().map(ReportController::view).toList());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> runById(@PathVariable Long id) { return ResponseEntity.ok(view(service.get(id))); }

    /** The file exactly as sealed; the seal travels in a header. */
    @GetMapping("/runs/{id}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable Long id) {
        ReportRun r = service.get(id);
        String name = r.getReportCode().toLowerCase() + "_" + r.getPeriodFrom() + "_" + r.getPeriodTo() + "_" + r.getId() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("X-Report-SHA256", r.getSha256())
                .body(r.getCsv().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/runs/{id}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable Long id) {
        ReportRun r = service.get(id);
        return ResponseEntity.ok(Map.of("runId", r.getId(), "sha256", r.getSha256(), "valid", service.verify(r)));
    }

    /** Live data, nothing recorded. */
    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable String code,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                       @RequestParam(required = false) Integer days,
                                                       @RequestParam(required = false) BigDecimal threshold) {
        return ResponseEntity.ok(view(service.generate(code, from, to, new Options(days, threshold))));
    }

    /** Generate, seal and keep; returns the run's metadata. */
    @PostMapping("/{code}/runs")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String code,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) Integer days,
                                                   @RequestParam(required = false) BigDecimal threshold,
                                                   @RequestBody(required = false) RunBody body) {
        ReportRun r = service.run(code, from, to, new Options(days, threshold), body != null ? body.by() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(r));
    }

    private static Map<String, Object> view(Table t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.definition().code());
        m.put("name", t.definition().name());
        m.put("tag", t.definition().tag());
        m.put("from", t.from());
        m.put("to", t.to());
        m.put("params", t.params());
        m.put("columns", t.columns());
        m.put("rows", t.rows());
        m.put("summary", t.summary());
        m.put("truncated", t.truncated());
        return m;
    }

    private static Map<String, Object> view(ReportRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getReportCode());
        m.put("title", r.getTitle());
        m.put("from", r.getPeriodFrom());
        m.put("to", r.getPeriodTo());
        m.put("params", r.getParams());
        m.put("generatedBy", r.getGeneratedBy());
        m.put("generatedAt", r.getCreatedAt());
        m.put("rowCount", r.getRowCount());
        m.put("format", r.getFormat());
        m.put("sha256", r.getSha256());
        m.put("size", r.getCsv() != null ? r.getCsv().getBytes(StandardCharsets.UTF_8).length : 0);
        return m;
    }
}
