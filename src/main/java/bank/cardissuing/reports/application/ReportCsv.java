package bank.cardissuing.reports.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Plain RFC-4180 CSV (comma, double-quote escaping, CRLF) and its SHA-256 seal. */
public final class ReportCsv {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String write(List<String> columns, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        line(sb, columns.stream().map(c -> (Object) c).toList());
        for (List<Object> r : rows) line(sb, r);
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(values.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof BigDecimal d) s = d.stripTrailingZeros().scale() <= 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        else if (v instanceof LocalDateTime t) s = t.format(TS);
        else if (v instanceof java.sql.Timestamp t) s = t.toLocalDateTime().format(TS);
        else if (v instanceof LocalDate d) s = d.toString();
        else if (v instanceof java.sql.Date d) s = d.toLocalDate().toString();
        else s = String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public static String sha256(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ReportCsv() { }
}
