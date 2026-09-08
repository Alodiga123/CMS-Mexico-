package bank.cardissuing.reports;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.reports.application.ReportCatalog;
import bank.cardissuing.reports.application.ReportCsv;
import bank.cardissuing.reports.application.ReportService;
import bank.cardissuing.reports.application.ReportService.Options;
import bank.cardissuing.reports.application.ReportSettings;
import bank.cardissuing.reports.domain.ReportRun;
import bank.cardissuing.reports.infrastructure.ReportRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ReportRunRepository runs;
    @Mock AuditService audit;
    ReportSettings settings = new ReportSettings();
    ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(jdbc, runs, settings, audit);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(new ArrayList<>());
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(new ArrayList<>());
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(runs.save(any())).thenAnswer(inv -> { ReportRun r = inv.getArgument(0); r.setId(77L); return r; });
    }

    @Test
    void catalogHasTheSixMockupReports_andRejectsUnknownCodes() {
        assertEquals(6, ReportCatalog.ALL.size());
        assertEquals(6, ReportCatalog.ALL.stream().map(ReportCatalog.Definition::code).distinct().count());
        assertEquals("Serie R24 · Banxico", ReportCatalog.get("r24_banxico").name());
        assertEquals("REPORT_NOT_FOUND", assertThrows(BusinessException.class, () -> service.generate("NOPE", null, null, null)).getErrorCode());
    }

    @Test
    void periodDefaults_dailyIsToday_othersLookBack() {
        LocalDate today = LocalDate.now();
        var daily = service.generate(ReportCatalog.DAILY_ISSUANCE, null, null, null);
        assertEquals(today, daily.from());
        assertEquals(today, daily.to());
        var pld = service.generate(ReportCatalog.PLD_UIF, null, null, null);
        assertEquals(today.minusDays(settings.getDefaultPeriodDays()), pld.from());
        assertEquals("REPORT_BAD_PERIOD", assertThrows(BusinessException.class,
                () -> service.generate(ReportCatalog.PLD_UIF, today, today.minusDays(1), null)).getErrorCode());
    }

    @Test
    void pld_usesConfiguredThresholds_orTheOneAsked() {
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        var t = service.generate(ReportCatalog.PLD_UIF, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), new Options(null, new BigDecimal("500")));
        verify(jdbc, atLeastOnce()).queryForList(contains("RELEVANTE"), p.capture());
        assertEquals(new BigDecimal("500"), p.getValue().getValue("relevant"));
        assertEquals(LocalDate.of(2026, 9, 8), p.getValue().getValue("toExcl"));
        assertEquals(new BigDecimal("500"), t.params().get("umbralRelevante"));
        assertEquals(List.of("tipo", "fecha", "tarjeta_id", "last4", "cliente", "monto", "comercio", "pais", "canal", "motivo", "referencia"), t.columns());
    }

    @Test
    void inactiveCards_defaultsTo90Days_andCapsRows() {
        settings.setMaxRows(2);
        List<Map<String, Object>> three = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tarjeta_id", (long) i);
            r.put("last4", "000" + i);
            three.add(r);
        }
        when(jdbc.queryForList(contains("dias_inactiva"), any(SqlParameterSource.class))).thenReturn(three);
        var t = service.generate(ReportCatalog.INACTIVE_CARDS, null, null, null);
        assertEquals(90, t.params().get("dias"));
        assertEquals(2, t.rows().size());
        assertTrue(t.truncated());
        assertEquals(3, t.summary().get("filas"));
        assertEquals(1L, t.rows().get(0).get(0));
    }

    @Test
    void run_sealsTheCsv_keepsIt_andAudits() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fecha", LocalDate.of(2026, 9, 7));
        row.put("emitidas", 3L);
        row.put("activadas", 1L);
        row.put("bloqueadas", 0L);
        row.put("canceladas", 0L);
        row.put("aut_aprobadas", 5L);
        row.put("aut_rechazadas", 2L);
        row.put("monto_aprobado", new BigDecimal("120.50"));
        when(jdbc.queryForList(contains("generate_series"), any(SqlParameterSource.class))).thenReturn(new ArrayList<>(List.of(row)));
        ReportRun r = service.run(ReportCatalog.DAILY_ISSUANCE, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), null, "auditoria");
        assertEquals(1, r.getRowCount());
        assertTrue(r.getCsv().startsWith("fecha,emitidas,activadas,bloqueadas,canceladas,aut_aprobadas,aut_rechazadas,monto_aprobado\r\n2026-09-07,3,1,0,0,5,2,120.50\r\n"));
        assertEquals(ReportCsv.sha256(r.getCsv()), r.getSha256());
        assertTrue(service.verify(r));
        r.setCsv(r.getCsv().replace("120.50", "120.51"));
        assertFalse(service.verify(r));
        verify(audit).log("GENERATE_REPORT_DAILY_ISSUANCE", "ReportRun", "77", "auditoria");
    }
}
