package bank.cardissuing.reports.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.reports.application.ReportCatalog.Definition;
import bank.cardissuing.reports.domain.ReportRun;
import bank.cardissuing.reports.infrastructure.ReportRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds each catalogue report straight from the operational tables (read-only SQL),
 * and records a run as sealed evidence when asked to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportRunRepository runs;
    private final ReportSettings settings;
    private final AuditService audit;

    /** What a report looks like before it is a file: columns, rows and a few headline numbers. */
    public record Table(Definition definition, LocalDate from, LocalDate to, Map<String, Object> params,
                        List<String> columns, List<List<Object>> rows, Map<String, Object> summary, boolean truncated) { }

    /** Parameters that are not the period. */
    public record Options(Integer days, BigDecimal threshold) {
        public static Options none() { return new Options(null, null); }
    }

    public Table generate(String code, LocalDate from, LocalDate to, Options opts) {
        Definition def = ReportCatalog.get(code);
        Options o = opts == null ? Options.none() : opts;
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from
                : (def.code().equals(ReportCatalog.DAILY_ISSUANCE) ? t : t.minusDays(settings.getDefaultPeriodDays()));
        if (f.isAfter(t)) throw new BusinessException("REPORT_BAD_PERIOD", "'from' is after 'to'", HttpStatus.BAD_REQUEST);
        Map<String, Object> params = new LinkedHashMap<>();
        return switch (def.code()) {
            case ReportCatalog.DAILY_ISSUANCE -> dailyIssuance(def, f, t, params);
            case ReportCatalog.PROCESSOR_RECONCILIATION -> processorReconciliation(def, f, t, params);
            case ReportCatalog.R24_BANXICO -> r24(def, f, t, params);
            case ReportCatalog.PLD_UIF -> pld(def, f, t, o, params);
            case ReportCatalog.DISPUTES_ACTIVITY -> disputes(def, f, t, params);
            case ReportCatalog.INACTIVE_CARDS -> inactiveCards(def, f, t, o, params);
            default -> throw new BusinessException("REPORT_NOT_FOUND", "Unknown report '" + code + "'", HttpStatus.NOT_FOUND);
        };
    }

    /** Generate, seal and keep. */
    @Transactional
    public ReportRun run(String code, LocalDate from, LocalDate to, Options opts, String by) {
        Table t = generate(code, from, to, opts);
        String csv = ReportCsv.write(t.columns(), t.rows());
        ReportRun r = new ReportRun();
        r.setReportCode(t.definition().code());
        r.setTitle(t.definition().name());
        r.setPeriodFrom(t.from());
        r.setPeriodTo(t.to());
        r.setParams(t.params().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";")));
        r.setGeneratedBy(bank.cardissuing.common.security.CmsPrincipal.auditName(by));
        r.setRowCount(t.rows().size());
        r.setCsv(csv);
        r.setSha256(ReportCsv.sha256(csv));
        r = runs.save(r);
        audit.log("GENERATE_REPORT_" + r.getReportCode(), "ReportRun", String.valueOf(r.getId()), r.getGeneratedBy());
        log.info("Report {} run #{} by {}: {} rows, sha256 {}", r.getReportCode(), r.getId(), r.getGeneratedBy(), r.getRowCount(), r.getSha256());
        return r;
    }

    public ReportRun get(Long id) {
        return runs.findById(id).orElseThrow(() -> new BusinessException("REPORT_RUN_NOT_FOUND", "Report run " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public List<ReportRun> recent(String code) {
        return code == null || code.isBlank() ? runs.findTop100ByOrderByCreatedAtDesc()
                : runs.findTop100ByReportCodeOrderByCreatedAtDesc(code.trim().toUpperCase());
    }

    /** Is the stored file still exactly what was sealed? */
    public boolean verify(ReportRun r) {
        return r.getCsv() != null && ReportCsv.sha256(r.getCsv()).equals(r.getSha256());
    }

    // ------------------------------------------------------------------ reports

    private Table dailyIssuance(Definition def, LocalDate from, LocalDate to, Map<String, Object> params) {
        String sql = """
                with days as (select generate_series(:from::date, :to::date, interval '1 day')::date d)
                select d.d as fecha,
                  (select count(*) from cards c where c.created_at >= d.d and c.created_at < d.d + 1) as emitidas,
                  (select count(*) from audit_logs a where a."timestamp" >= d.d and a."timestamp" < d.d + 1
                      and a.action in ('CARD_STATUS_ACTIVE','ACTIVATE_PLASTIC')) as activadas,
                  (select count(*) from audit_logs a where a."timestamp" >= d.d and a."timestamp" < d.d + 1
                      and (a.action in ('CARD_STATUS_BLOCKED','CARD_STATUS_SUSPENDED') or a.action like 'BLOCK_CARD_%')) as bloqueadas,
                  (select count(*) from audit_logs a where a."timestamp" >= d.d and a."timestamp" < d.d + 1
                      and a.action in ('CARD_STATUS_CANCELED','CARD_STATUS_CLOSED')) as canceladas,
                  (select count(*) from authorization_attempts t where t.created_at >= d.d and t.created_at < d.d + 1 and t.approved) as aut_aprobadas,
                  (select count(*) from authorization_attempts t where t.created_at >= d.d and t.created_at < d.d + 1 and not t.approved) as aut_rechazadas,
                  (select coalesce(sum(t.amount),0) from authorization_attempts t where t.created_at >= d.d and t.created_at < d.d + 1 and t.approved) as monto_aprobado
                from days d order by d.d
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, period(from, to));
        List<String> cols = List.of("fecha", "emitidas", "activadas", "bloqueadas", "canceladas", "aut_aprobadas", "aut_rechazadas", "monto_aprobado");
        Map<String, Object> summary = totals(rows, cols.subList(1, cols.size()));
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList("select status, count(*) n from cards group by status order by status", Map.of())) {
            byStatus.put((String) m.get("status"), m.get("n"));
        }
        summary.put("tarjetasPorEstado", byStatus);
        return table(def, from, to, params, cols, rows, summary);
    }

    private Table processorReconciliation(Definition def, LocalDate from, LocalDate to, Map<String, Object> params) {
        String sql = """
                select h.created_at as fecha, h.approval_code as autorizacion, h.card_id as tarjeta_id, c.last4,
                       h.merchant_id as comercio_id, h.merchant_name as comercio, h.transaction_type as tipo,
                       h.amount as autorizado, coalesce(h.captured_amount, 0) as compensado, h.status as estado,
                       case when h.status = 'CAPTURED' then h.amount - coalesce(h.captured_amount, 0) end as diferencia,
                       h.capture_ref as ref_compensacion, h.external_ref as ref_core, h.expires_at as vence
                from authorization_holds h join cards c on c.id = h.card_id
                where h.created_at >= :from and h.created_at < :toExcl
                order by h.created_at
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, period(from, to));
        List<String> cols = List.of("fecha", "autorizacion", "tarjeta_id", "last4", "comercio_id", "comercio", "tipo",
                "autorizado", "compensado", "estado", "diferencia", "ref_compensacion", "ref_core", "vence");
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> s = byStatus.computeIfAbsent((String) r.get("estado"), k -> {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("n", 0L); init.put("autorizado", BigDecimal.ZERO); init.put("compensado", BigDecimal.ZERO);
                return init;
            });
            s.put("n", (Long) s.get("n") + 1);
            s.put("autorizado", ((BigDecimal) s.get("autorizado")).add(num(r.get("autorizado"))));
            s.put("compensado", ((BigDecimal) s.get("compensado")).add(num(r.get("compensado"))));
        }
        summary.put("porEstado", byStatus);
        summary.put("compensacionesParciales", rows.stream().filter(r -> "CAPTURED".equals(r.get("estado")) && num(r.get("diferencia")).signum() > 0).count());
        summary.put("pendientesDeCompensar", rows.stream().filter(r -> "HELD".equals(r.get("estado"))).count());
        summary.put("diferenciasAbiertasConCore", jdbc.queryForObject("select count(*) from reconciliation_items where status = 'OPEN'", Map.of(), Long.class));
        return table(def, from, to, params, cols, rows, summary);
    }

    private Table r24(Definition def, LocalDate from, LocalDate to, Map<String, Object> params) {
        String sql = """
                select p.id as product_id, p.product_code as producto, p.product_name as nombre, p.bin, p.card_type as tipo,
                       p.payment_type as modalidad, p.network as marca, p.currency as moneda,
                       (select count(*) from cards c where c.product_id = p.id and c.created_at >= :from and c.created_at < :toExcl) as emitidas_periodo,
                       (select count(*) from cards c where c.product_id = p.id) as emitidas_total,
                       (select count(*) from cards c where c.product_id = p.id and c.status = 'ACTIVE') as vigentes,
                       (select count(*) from cards c where c.product_id = p.id and c.status in ('BLOCKED','SUSPENDED')) as bloqueadas,
                       (select count(*) from cards c where c.product_id = p.id and c.status in ('CANCELED','CLOSED','EXPIRED')) as canceladas,
                       (select count(*) from authorization_attempts t join cards c on c.id = t.card_id
                           where c.product_id = p.id and t.approved and t.created_at >= :from and t.created_at < :toExcl) as operaciones,
                       (select coalesce(sum(t.amount),0) from authorization_attempts t join cards c on c.id = t.card_id
                           where c.product_id = p.id and t.approved and t.created_at >= :from and t.created_at < :toExcl) as monto
                from card_products p where p.active order by p.product_code
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, period(from, to));
        List<Map<String, Object>> perChannel = jdbc.queryForList("""
                select c.product_id, t.channel, count(*) n
                from authorization_attempts t join cards c on c.id = t.card_id
                where t.approved and t.created_at >= :from and t.created_at < :toExcl group by 1, 2
                """, period(from, to));
        List<String> cols = new ArrayList<>(List.of("producto", "nombre", "bin", "tipo", "modalidad", "marca", "moneda",
                "emitidas_periodo", "emitidas_total", "vigentes", "bloqueadas", "canceladas", "operaciones", "monto"));
        for (Channel ch : Channel.values()) cols.add("ops_" + ch.name().toLowerCase());
        for (Map<String, Object> r : rows) {
            long pid = ((Number) r.get("product_id")).longValue();
            for (Channel ch : Channel.values()) {
                long n = perChannel.stream()
                        .filter(m -> ((Number) m.get("product_id")).longValue() == pid && ch.name().equals(m.get("channel")))
                        .mapToLong(m -> ((Number) m.get("n")).longValue()).sum();
                r.put("ops_" + ch.name().toLowerCase(), n);
            }
        }
        Map<String, Object> summary = totals(rows, cols.subList(7, cols.size()));
        summary.put("productosDebito", rows.stream().filter(r -> !"CREDIT".equals(r.get("tipo"))).count());
        return table(def, from, to, params, cols, rows, summary);
    }

    private Table pld(Definition def, LocalDate from, LocalDate to, Options o, Map<String, Object> params) {
        BigDecimal relevant = o.threshold() != null ? o.threshold() : settings.getRelevantThreshold();
        BigDecimal accumulated = settings.getAccumulatedThreshold();
        params.put("umbralRelevante", relevant);
        params.put("umbralAcumulado", accumulated);
        params.put("scoreInusual", settings.getUnusualRiskScore());
        MapSqlParameterSource p = period(from, to).addValue("relevant", relevant).addValue("accumulated", accumulated)
                .addValue("score", settings.getUnusualRiskScore());
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("""
                select 'RELEVANTE' as tipo, t.created_at as fecha, t.card_id as tarjeta_id, c.last4, cu.full_name as cliente,
                       t.amount as monto, t.merchant_name as comercio, t.country_code as pais, t.channel as canal,
                       'Operación individual >= umbral' as motivo, t.approval_code as referencia
                from authorization_attempts t join cards c on c.id = t.card_id join customers cu on cu.id = c.customer_id
                where t.approved and t.amount >= :relevant and t.created_at >= :from and t.created_at < :toExcl
                order by t.created_at
                """, p));
        rows.addAll(jdbc.queryForList("""
                select 'ACUMULADA' as tipo, max(t.created_at) as fecha, t.card_id as tarjeta_id, c.last4, cu.full_name as cliente,
                       sum(t.amount) as monto, count(*) || ' operaciones' as comercio, null as pais, null as canal,
                       'Acumulado del periodo >= umbral' as motivo, null as referencia
                from authorization_attempts t join cards c on c.id = t.card_id join customers cu on cu.id = c.customer_id
                where t.approved and t.created_at >= :from and t.created_at < :toExcl
                group by t.card_id, c.last4, cu.full_name having sum(t.amount) >= :accumulated
                order by 6 desc
                """, p));
        rows.addAll(jdbc.queryForList("""
                select 'INUSUAL' as tipo, a.created_at as fecha, a.card_id as tarjeta_id, c.last4, cu.full_name as cliente,
                       a.amount as monto, a.merchant_name as comercio, null as pais, null as canal,
                       'Alerta ' || a.type || ' (score ' || a.risk_score || '): ' || coalesce(a.reasons, '') as motivo,
                       'ALERT-' || a.id as referencia
                from fraud_alerts a left join cards c on c.id = a.card_id left join customers cu on cu.id = c.customer_id
                where a.created_at >= :from and a.created_at < :toExcl
                order by a.created_at
                """, p));
        rows.addAll(jdbc.queryForList("""
                select 'INUSUAL' as tipo, t.created_at as fecha, t.card_id as tarjeta_id, c.last4, cu.full_name as cliente,
                       t.amount as monto, t.merchant_name as comercio, t.country_code as pais, t.channel as canal,
                       'Aprobada con score ' || t.risk_score || ': ' || coalesce(t.risk_reasons, '') as motivo, t.approval_code as referencia
                from authorization_attempts t join cards c on c.id = t.card_id join customers cu on cu.id = c.customer_id
                where t.approved and t.risk_score >= :score and t.created_at >= :from and t.created_at < :toExcl
                order by t.created_at
                """, p));
        List<String> cols = List.of("tipo", "fecha", "tarjeta_id", "last4", "cliente", "monto", "comercio", "pais", "canal", "motivo", "referencia");
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String tipo : List.of("RELEVANTE", "ACUMULADA", "INUSUAL")) {
            summary.put(tipo.toLowerCase(), rows.stream().filter(r -> tipo.equals(r.get("tipo"))).count());
        }
        summary.put("montoRelevante", rows.stream().filter(r -> "RELEVANTE".equals(r.get("tipo"))).map(r -> num(r.get("monto"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        return table(def, from, to, params, cols, rows, summary);
    }

    private Table disputes(Definition def, LocalDate from, LocalDate to, Map<String, Object> params) {
        String sql = """
                select r.code as razon, r.description as descripcion, d.status as estado, count(*) as casos,
                       coalesce(sum(d.amount), 0) as monto,
                       sum(case when extract(day from coalesce(d.resolved_at, now()) - d.created_at) <= 15 then 1 else 0 end) as dias_0_15,
                       sum(case when extract(day from coalesce(d.resolved_at, now()) - d.created_at) between 16 and 30 then 1 else 0 end) as dias_16_30,
                       sum(case when extract(day from coalesce(d.resolved_at, now()) - d.created_at) between 31 and 60 then 1 else 0 end) as dias_31_60,
                       sum(case when extract(day from coalesce(d.resolved_at, now()) - d.created_at) > 60 then 1 else 0 end) as dias_mas_60,
                       sum(case when d.deadline_breached then 1 else 0 end) as fuera_de_plazo,
                       sum(case when d.provisional_credit then 1 else 0 end) as con_abono_provisional,
                       sum(case when d.outcome = 'CUSTOMER' then 1 else 0 end) as a_favor_cliente,
                       sum(case when d.outcome = 'MERCHANT' then 1 else 0 end) as a_favor_comercio
                from disputes d join dispute_reasons r on r.id = d.reason_id
                where d.created_at >= :from and d.created_at < :toExcl
                group by r.code, r.description, d.status order by r.code, d.status
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, period(from, to));
        List<String> cols = List.of("razon", "descripcion", "estado", "casos", "monto", "dias_0_15", "dias_16_30", "dias_31_60", "dias_mas_60",
                "fuera_de_plazo", "con_abono_provisional", "a_favor_cliente", "a_favor_comercio");
        Map<String, Object> summary = totals(rows, cols.subList(3, cols.size()));
        summary.put("abiertas", rows.stream().filter(r -> !List.of("RESOLVED_CUSTOMER", "RESOLVED_MERCHANT", "WITHDRAWN").contains((String) r.get("estado")))
                .mapToLong(r -> ((Number) r.get("casos")).longValue()).sum());
        return table(def, from, to, params, cols, rows, summary);
    }

    private Table inactiveCards(Definition def, LocalDate from, LocalDate to, Options o, Map<String, Object> params) {
        int days = o.days() != null && o.days() > 0 ? o.days() : settings.getInactiveDays();
        params.put("dias", days);
        String sql = """
                select c.id as tarjeta_id, c.last4, cu.full_name as cliente, p.product_name as producto, c.card_category as categoria,
                       c.created_at as emitida,
                       (select max(t.created_at) from authorization_attempts t where t.card_id = c.id and t.approved) as ultima_operacion,
                       current_date - coalesce((select max(t.created_at) from authorization_attempts t where t.card_id = c.id and t.approved)::date, c.created_at::date) as dias_inactiva,
                       c.expiry_date as vence
                from cards c join customers cu on cu.id = c.customer_id join card_products p on p.id = c.product_id
                where c.status = 'ACTIVE'
                  and not exists (select 1 from authorization_attempts t where t.card_id = c.id and t.approved and t.created_at >= current_date - :days)
                  and c.created_at < current_date - :days
                order by dias_inactiva desc, c.id
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource("days", days));
        List<String> cols = List.of("tarjeta_id", "last4", "cliente", "producto", "categoria", "emitida", "ultima_operacion", "dias_inactiva", "vence");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inactivas", rows.size());
        summary.put("nuncaUsadas", rows.stream().filter(r -> r.get("ultima_operacion") == null).count());
        summary.put("tarjetasActivas", jdbc.queryForObject("select count(*) from cards where status = 'ACTIVE'", Map.of(), Long.class));
        return table(def, from, to, params, cols, rows, summary);
    }

    // ------------------------------------------------------------------ helpers

    private static MapSqlParameterSource period(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to).addValue("toExcl", to.plusDays(1));
    }

    private Table table(Definition def, LocalDate from, LocalDate to, Map<String, Object> params, List<String> cols,
                        List<Map<String, Object>> rows, Map<String, Object> summary) {
        boolean truncated = rows.size() > settings.getMaxRows();
        List<List<Object>> data = rows.stream().limit(settings.getMaxRows())
                .map(r -> cols.stream().map(r::get).collect(Collectors.toCollection(ArrayList<Object>::new)))
                .collect(Collectors.toList());
        summary.put("filas", rows.size());
        return new Table(def, from, to, params, cols, data, summary, truncated);
    }

    private static Map<String, Object> totals(List<Map<String, Object>> rows, List<String> numericCols) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String c : numericCols) {
            BigDecimal sum = rows.stream().map(r -> num(r.get(c))).reduce(BigDecimal.ZERO, BigDecimal::add);
            t.put(c, sum.stripTrailingZeros().scale() <= 0 ? (Object) sum.longValue() : sum);
        }
        return t;
    }

    static BigDecimal num(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }
}
