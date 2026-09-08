package bank.cardissuing.iso8583;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * ISO 8583 (1987) in ASCII: MTI, hex bitmap (primary, plus secondary when a field above
 * 64 is present), then each present field in numeric order. Numeric and text fields are
 * carried as ASCII; binary fields (52, 55, 128) travel as hex text. Lengths of LLVAR /
 * LLLVAR fields are ASCII digits. This is the dialect the local switch simulator speaks;
 * a network's own variant is a matter of the spec table, not of this class.
 */
public final class Iso8583Codec {

    /** How a field is laid out: fixed length, or variable with 2 / 3 length digits. */
    public record Spec(int field, int length, int lengthDigits, String name) {
        boolean fixed() { return lengthDigits == 0; }
    }

    public static final Map<Integer, Spec> SPECS = new TreeMap<>();

    static {
        fixed(3, 6, "processing code");
        fixed(4, 12, "amount, transaction");
        fixed(7, 10, "transmission date and time MMDDhhmmss");
        fixed(11, 6, "STAN");
        fixed(12, 6, "local time hhmmss");
        fixed(13, 4, "local date MMDD");
        fixed(14, 4, "expiration date YYMM");
        fixed(15, 4, "settlement date");
        fixed(18, 4, "merchant category code");
        fixed(19, 3, "acquiring institution country code");
        fixed(22, 3, "POS entry mode");
        fixed(23, 3, "PAN sequence number");
        fixed(25, 2, "POS condition code");
        fixed(26, 2, "POS PIN capture code");
        fixed(37, 12, "retrieval reference number");
        fixed(38, 6, "authorization id response");
        fixed(39, 2, "response code");
        fixed(41, 8, "card acceptor terminal id");
        fixed(42, 15, "card acceptor id");
        fixed(43, 40, "card acceptor name / location");
        fixed(49, 3, "currency code, transaction");
        fixed(52, 16, "PIN data (hex)");
        fixed(70, 3, "network management information code");
        fixed(90, 42, "original data elements");
        fixed(95, 42, "replacement amounts");
        fixed(128, 16, "MAC (hex)");
        var2(2, 19, "PAN");
        var2(32, 11, "acquiring institution id");
        var2(33, 11, "forwarding institution id");
        var2(35, 37, "track 2");
        var3(45, 76, "track 1");
        var3(48, 999, "additional data, private");
        var3(54, 120, "additional amounts");
        var3(55, 999, "ICC data (hex)");
        var3(60, 999, "private 60");
        var3(61, 999, "private 61");
        var3(62, 999, "private 62");
        var3(63, 999, "private 63");
        var2(102, 28, "account id 1");
        var2(103, 28, "account id 2");
    }

    private static void fixed(int f, int len, String name) { SPECS.put(f, new Spec(f, len, 0, name)); }
    private static void var2(int f, int max, String name) { SPECS.put(f, new Spec(f, max, 2, name)); }
    private static void var3(int f, int max, String name) { SPECS.put(f, new Spec(f, max, 3, name)); }

    public static byte[] encode(Iso8583Message m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getMti());
        boolean secondary = m.getFields().keySet().stream().anyMatch(f -> f > 64);
        long[] bits = new long[2];
        if (secondary) bits[0] |= 1L << 63;
        for (int f : m.getFields().keySet()) {
            if (f < 2 || f > 128) throw new IllegalArgumentException("field " + f + " out of range");
            int idx = (f - 1) / 64; int bit = 63 - ((f - 1) % 64);
            bits[idx] |= 1L << bit;
        }
        sb.append(String.format("%016X", bits[0]));
        if (secondary) sb.append(String.format("%016X", bits[1]));
        for (Map.Entry<Integer, String> e : m.getFields().entrySet()) {
            Spec s = SPECS.get(e.getKey());
            if (s == null) throw new IllegalArgumentException("no spec for field " + e.getKey());
            String v = e.getValue();
            if (s.fixed()) {
                if (v.length() != s.length()) throw new IllegalArgumentException("field " + s.field() + " (" + s.name() + ") must be " + s.length() + " chars, got " + v.length());
                sb.append(v);
            } else {
                if (v.length() > s.length()) throw new IllegalArgumentException("field " + s.field() + " longer than " + s.length());
                sb.append(String.format("%0" + s.lengthDigits() + "d", v.length())).append(v);
            }
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static Iso8583Message decode(byte[] data) {
        String s = new String(data, StandardCharsets.US_ASCII);
        int pos = 0;
        if (s.length() < 20) throw new IllegalArgumentException("message too short");
        Iso8583Message m = new Iso8583Message(s.substring(0, 4));
        pos = 4;
        long primary = Long.parseUnsignedLong(s.substring(pos, pos + 16), 16);
        pos += 16;
        long secondary = 0;
        if ((primary & (1L << 63)) != 0) { secondary = Long.parseUnsignedLong(s.substring(pos, pos + 16), 16); pos += 16; }
        for (int f = 2; f <= 128; f++) {
            boolean present = f <= 64 ? (primary & (1L << (64 - f))) != 0 : (secondary & (1L << (128 - f))) != 0;
            if (!present) continue;
            Spec spec = SPECS.get(f);
            if (spec == null) throw new IllegalArgumentException("field " + f + " present but unknown to this codec");
            int len;
            if (spec.fixed()) len = spec.length();
            else {
                len = Integer.parseInt(s.substring(pos, pos + spec.lengthDigits()));
                pos += spec.lengthDigits();
                if (len > spec.length()) throw new IllegalArgumentException("field " + f + " length " + len + " over " + spec.length());
            }
            if (pos + len > s.length()) throw new IllegalArgumentException("message truncated in field " + f);
            m.set(f, s.substring(pos, pos + len));
            pos += len;
        }
        return m;
    }
}
