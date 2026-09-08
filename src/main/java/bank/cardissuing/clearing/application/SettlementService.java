package bank.cardissuing.clearing.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.clearing.domain.SettlementCycle;
import bank.cardissuing.clearing.infrastructure.ClearingBatchRepository;
import bank.cardissuing.clearing.infrastructure.SettlementCycleRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.CmsPrincipal;
import bank.cardissuing.reports.application.ReportCsv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settlement: one position per network and cycle date, fed by every clearing batch of
 * that cycle. Closing freezes the numbers and seals a settlement file for treasury;
 * paying records the transfer that squared it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementCycleRepository cycles;
    private final ClearingBatchRepository batches;
    private final ClearingSettings settings;
    private final AuditService audit;

    @Transactional
    public SettlementCycle addBatch(ClearingBatch b) {
        SettlementCycle c = cycles.findByNetworkAndCycleDate(b.getNetwork(), b.getCycleDate()).orElseGet(() -> {
            SettlementCycle n = new SettlementCycle();
            n.setNetwork(b.getNetwork()); n.setCycleDate(b.getCycleDate()); n.setCurrency(settings.getCurrency());
            return cycles.save(n);
        });
        if (c.getStatus() != SettlementCycle.Status.OPEN) {
            throw new BusinessException("SETTLEMENT_CYCLE_CLOSED", "Cycle " + c.getNetwork() + " " + c.getCycleDate() + " is " + c.getStatus() + "; a late file needs a new cycle date", HttpStatus.CONFLICT);
        }
        c.add(b);
        return cycles.save(c);
    }

    @Transactional
    public SettlementCycle close(Long id, String by) {
        SettlementCycle c = get(id);
        if (c.getStatus() != SettlementCycle.Status.OPEN) throw new BusinessException("SETTLEMENT_CYCLE_CLOSED", "Cycle " + id + " is already " + c.getStatus(), HttpStatus.CONFLICT);
        String file = settlementFile(c);
        c.setSettlementFile(file);
        c.setSha256(ReportCsv.sha256(file));
        c.setStatus(SettlementCycle.Status.CLOSED);
        c.setClosedAt(LocalDateTime.now());
        c.setClosedBy(CmsPrincipal.auditName(by));
        c = cycles.save(c);
        audit.log("SETTLEMENT_CYCLE_CLOSED", "SettlementCycle", c.getId().toString(), c.getClosedBy());
        log.info("Settlement {} {} closed: net {} {} ({} presentments, {} exceptions)", c.getNetwork(), c.getCycleDate(), c.getNetPosition(), c.getCurrency(), c.getPresentmentsCount(), c.getExceptionCount());
        return c;
    }

    @Transactional
    public SettlementCycle markPaid(Long id, String paymentRef, String by) {
        SettlementCycle c = get(id);
        if (c.getStatus() != SettlementCycle.Status.CLOSED) throw new BusinessException("SETTLEMENT_CYCLE_NOT_CLOSED", "Close the cycle before paying it", HttpStatus.CONFLICT);
        if (paymentRef == null || paymentRef.isBlank()) throw new BusinessException("SETTLEMENT_PAYMENT_REF_REQUIRED", "paymentRef (SPEI / transfer reference) is required", HttpStatus.BAD_REQUEST);
        c.setStatus(SettlementCycle.Status.PAID);
        c.setPaidAt(LocalDateTime.now());
        c.setPaymentRef(paymentRef.trim());
        c = cycles.save(c);
        audit.log("SETTLEMENT_CYCLE_PAID", "SettlementCycle", c.getId().toString(), CmsPrincipal.auditName(by));
        return c;
    }

    public SettlementCycle get(Long id) {
        return cycles.findById(id).orElseThrow(() -> new BusinessException("SETTLEMENT_CYCLE_NOT_FOUND", "Settlement cycle " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public List<SettlementCycle> recent() { return cycles.findTop100ByOrderByCycleDateDescNetworkAsc(); }

    /** The settlement file for treasury: one line per batch, totals and the net position, CSV, sealed. */
    String settlementFile(SettlementCycle c) {
        List<String> cols = List.of("network", "cycle_date", "batch_id", "file", "records", "presentments", "presentments_amount", "reversals", "chargebacks", "interchange", "network_fees", "exceptions", "sha256");
        List<List<Object>> rows = new ArrayList<>();
        for (ClearingBatch b : batches.findBySettlementCycleIdOrderByCreatedAtAsc(c.getId())) {
            rows.add(Arrays.asList(b.getNetwork().name(), b.getCycleDate(), b.getId(), b.getFileName(), b.getRecordCount(), b.getPresentmentsCount(),
                    b.getPresentmentsAmount(), b.getReversalsAmount(), b.getChargebacksAmount(), b.getInterchangeAmount(), b.getFeesAmount(), b.getExceptionCount(), b.getSha256()));
        }
        rows.add(Arrays.asList(c.getNetwork().name(), c.getCycleDate(), "TOTAL", c.getBatchCount() + " batches", null, c.getPresentmentsCount(),
                c.getPresentmentsAmount(), c.getReversalsAmount(), c.getChargebacksAmount(), c.getInterchangeAmount(), c.getFeesAmount(), c.getExceptionCount(), null));
        rows.add(Arrays.asList(c.getNetwork().name(), c.getCycleDate(), "NET", c.getNetPosition().signum() >= 0 ? "ISSUER PAYS" : "ISSUER RECEIVES",
                null, null, c.getNetPosition().abs(), null, null, null, null, null, c.getCurrency()));
        return ReportCsv.write(cols, rows);
    }

    public static Network network(String s) {
        try { return Network.valueOf(s.trim().toUpperCase()); } catch (RuntimeException e) { throw new BusinessException("CLEARING_BAD_NETWORK", "network must be VISA, MASTERCARD or DOMESTIC", HttpStatus.BAD_REQUEST); }
    }

    public static LocalDate cycleDate(String s) { return s == null || s.isBlank() ? LocalDate.now() : LocalDate.parse(s); }
}
