package bank.cardissuing.iso8583;

import java.util.Map;
import java.util.TreeMap;

/** An ISO 8583 message: a four-digit type and its data elements, by number. */
public class Iso8583Message {

    private String mti;
    private final Map<Integer, String> fields = new TreeMap<>();

    public Iso8583Message() { }

    public Iso8583Message(String mti) { this.mti = mti; }

    public String getMti() { return mti; }

    public void setMti(String mti) { this.mti = mti; }

    public Map<Integer, String> getFields() { return fields; }

    public String get(int field) { return fields.get(field); }

    public boolean has(int field) { return fields.containsKey(field) && fields.get(field) != null; }

    public Iso8583Message set(int field, String value) {
        if (value == null) fields.remove(field); else fields.put(field, value);
        return this;
    }

    /** The reply type for a request: 0100 -> 0110, 0200 -> 0210, 0400 -> 0410, 0420 -> 0430, 0800 -> 0810. */
    public String responseMti() {
        int t = Integer.parseInt(mti);
        return String.format("%04d", t + 10);
    }

    /** A response that echoes the identifying elements of this message. */
    public Iso8583Message reply() {
        Iso8583Message r = new Iso8583Message(responseMti());
        for (int f : new int[] {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49}) if (has(f)) r.set(f, get(f));
        return r;
    }

    public boolean isRequest() { return mti != null && mti.length() == 4 && (mti.charAt(2) == '0' || mti.charAt(2) == '2'); }

    public boolean isAdvice() { return mti != null && mti.length() == 4 && mti.charAt(2) == '2'; }

    public boolean isAuthorization() { return mti != null && (mti.startsWith("01") || mti.startsWith("02")); }

    public boolean isReversal() { return mti != null && mti.startsWith("04"); }

    public boolean isNetwork() { return mti != null && mti.startsWith("08"); }

    /** Masked, for logs: the PAN keeps its first six and last four, the PIN block and track are never shown. */
    public String describe() {
        StringBuilder sb = new StringBuilder(mti);
        fields.forEach((k, v) -> {
            String shown = switch (k) {
                case 2 -> v.length() > 10 ? v.substring(0, 6) + "******" + v.substring(v.length() - 4) : "****";
                case 35, 45, 52 -> "<" + v.length() + ">";
                default -> v.length() > 40 ? v.substring(0, 40) + "…" : v;
            };
            sb.append(' ').append(k).append('=').append(shown);
        });
        return sb.toString();
    }
}
