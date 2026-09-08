package bank.cardissuing.clearing.application;

import bank.cardissuing.clearing.domain.ClearingRecord;
import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The clearing file layout the CMS reads, "CMS-CLR 1.0": pipe-separated, one record per line.
 *
 *   HDR|CMS-CLR|1.0|network|cycleDate(yyyy-MM-dd)|fileId
 *   REC|type|pan|rrn|stan|approvalId|amount|currency|interchangeFee|mcc|merchantId|merchantName|txDate|reasonCode
 *   TRL|recordCount|totalAmount
 *
 * A network's own format (Visa BASE II / TC46, Mastercard IPM, the domestic 350-position
 * layout) is a translation into these records: one parser per format, same processing.
 */
public final class ClearingFileParser {

    public record Header(Network network, LocalDate cycleDate, String fileId) { }
    public record Line(int lineNo, ClearingRecord.Type type, String pan, String rrn, String stan, String approvalId, BigDecimal amount,
                       String currency, BigDecimal interchangeFee, String mcc, String merchantId, String merchantName, LocalDate txDate, String reasonCode) { }
    public record Trailer(int count, BigDecimal totalAmount) { }
    public record Parsed(Header header, List<Line> lines, Trailer trailer) { }

    public static Parsed parse(String content) {
        if (content == null || content.isBlank()) throw bad("empty file");
        String[] rows = content.replace("\r", "").split("\n");
        Header header = null; Trailer trailer = null;
        List<Line> lines = new ArrayList<>();
        int no = 0;
        for (String raw : rows) {
            no++;
            if (raw.isBlank()) continue;
            String[] f = raw.split("\\|", -1);
            switch (f[0]) {
                case "HDR" -> {
                    if (f.length < 6 || !"CMS-CLR".equals(f[1])) throw bad("line " + no + ": bad header");
                    header = new Header(parseNetwork(f[3]), LocalDate.parse(f[4]), f[5]);
                }
                case "REC" -> {
                    if (header == null) throw bad("line " + no + ": record before header");
                    if (f.length < 14) throw bad("line " + no + ": record needs 14 fields, has " + f.length);
                    ClearingRecord.Type type;
                    try { type = ClearingRecord.Type.valueOf(f[1].trim().toUpperCase()); } catch (IllegalArgumentException e) { throw bad("line " + no + ": unknown record type " + f[1]); }
                    lines.add(new Line(no, type, digits(f[2]), blank(f[3]), blank(f[4]), blank(f[5]), money(f[6], no), blank(f[7]),
                            f[8].isBlank() ? BigDecimal.ZERO : money(f[8], no), blank(f[9]), blank(f[10]), blank(f[11]),
                            f[12].isBlank() ? null : LocalDate.parse(f[12].trim()), blank(f[13])));
                }
                case "TRL" -> {
                    if (f.length < 3) throw bad("line " + no + ": bad trailer");
                    trailer = new Trailer(Integer.parseInt(f[1].trim()), money(f[2], no));
                }
                default -> throw bad("line " + no + ": unknown line type " + f[0]);
            }
        }
        if (header == null) throw bad("no header");
        if (trailer == null) throw bad("no trailer");
        if (trailer.count() != lines.size()) throw bad("trailer says " + trailer.count() + " records, file has " + lines.size());
        BigDecimal sum = lines.stream().filter(l -> l.type() != ClearingRecord.Type.FEE).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(trailer.totalAmount()) != 0) throw bad("trailer total " + trailer.totalAmount() + " differs from records " + sum);
        return new Parsed(header, lines, trailer);
    }

    /** Writes a file in the same layout (the network simulator and the settlement report use it). */
    public static String write(Header h, List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("HDR|CMS-CLR|1.0|").append(h.network()).append('|').append(h.cycleDate()).append('|').append(h.fileId()).append('\n');
        BigDecimal total = BigDecimal.ZERO;
        for (Line l : lines) {
            sb.append("REC|").append(l.type()).append('|').append(n(l.pan())).append('|').append(n(l.rrn())).append('|').append(n(l.stan())).append('|')
              .append(n(l.approvalId())).append('|').append(l.amount().toPlainString()).append('|').append(n(l.currency())).append('|')
              .append(l.interchangeFee() == null ? "" : l.interchangeFee().toPlainString()).append('|').append(n(l.mcc())).append('|')
              .append(n(l.merchantId())).append('|').append(n(l.merchantName()).replace("|", " ")).append('|')
              .append(l.txDate() == null ? "" : l.txDate().toString()).append('|').append(n(l.reasonCode())).append('\n');
            if (l.type() != ClearingRecord.Type.FEE) total = total.add(l.amount());
        }
        sb.append("TRL|").append(lines.size()).append('|').append(total.toPlainString()).append('\n');
        return sb.toString();
    }

    private static Network parseNetwork(String s) {
        try { return Network.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { throw bad("unknown network " + s); }
    }
    private static String digits(String s) { String d = s == null ? "" : s.replaceAll("\\D", ""); return d.isEmpty() ? null : d; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String n(String s) { return s == null ? "" : s; }
    private static BigDecimal money(String s, int line) {
        try { return new BigDecimal(s.trim()).setScale(2, java.math.RoundingMode.HALF_UP); } catch (RuntimeException e) { throw bad("line " + line + ": bad amount '" + s + "'"); }
    }
    private static BusinessException bad(String msg) { return new BusinessException("CLEARING_FILE_INVALID", msg, HttpStatus.UNPROCESSABLE_ENTITY); }

    private ClearingFileParser() { }
}
