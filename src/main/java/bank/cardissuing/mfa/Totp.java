package bank.cardissuing.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * TOTP RFC 6238 (HMAC-SHA1, paso de 30 s, 6 dígitos, secreto Base32) — compatible con Google
 * Authenticator / Authy. Segundo factor de la consola del CMS (PCI DSS 8.4.2/8.5). Sin dependencias.
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() { }

    /** Secreto aleatorio de 160 bits en Base32 (sin relleno). */
    public static String newSecret() {
        byte[] buf = new byte[20];
        RANDOM.nextBytes(buf);
        return base32Encode(buf);
    }

    /** URI otpauth:// para el código QR / alta manual en la app autenticadora. */
    public static String otpauthUri(String issuer, String account, String secret) {
        String iss = urlEncode(issuer);
        return "otpauth://totp/" + iss + ":" + urlEncode(account)
                + "?secret=" + secret + "&issuer=" + iss + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** ¿El código de 6 dígitos es válido para el secreto, ahora? Tolera ±1 paso por desfase de reloj. */
    public static boolean verify(String secret, String code) {
        if (secret == null || code == null) return false;
        String c = code.trim();
        if (!c.matches("\\d{6}")) return false;
        long step = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        byte[] key;
        try { key = base32Decode(secret); } catch (RuntimeException e) { return false; }
        for (long w = -1; w <= 1; w++) {
            if (constantTimeEquals(hotp(key, step + w), c)) return true;
        }
        return false;
    }

    /** Solo para pruebas: el código de 6 dígitos en un instante dado (segundos epoch). */
    static String codeAt(String secret, long epochSeconds) {
        return hotp(base32Decode(secret), epochSeconds / STEP_SECONDS);
    }

    private static String hotp(byte[] key, long counter) {
        try {
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0F;
            int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16) | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
            int otp = bin % 1_000_000;
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 no disponible", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String in = s.trim().replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0, bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : in.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Base32 inválido");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
