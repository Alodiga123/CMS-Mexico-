package bank.cardissuing.card.application;

import bank.cardissuing.common.exception.BusinessException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The only place that sees a full PAN at rest. Stored encrypted (AES-256-GCM, fresh nonce
 * per card) so the authorizer can hand it to the HSM, and hashed (HMAC-SHA256 with a
 * separate pepper) so an ISO message can find the card without decrypting anything.
 * Both keys come from the environment; the defaults are development only.
 */
@Component
@ConfigurationProperties(prefix = "cards.pan")
@Getter
@Setter
public class PanVault {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** AES-256 key, base64 (32 bytes). */
    private String encryptionKeyBase64 = "ZGV2LW9ubHktcGFuLWVuY3J5cHRpb24ta2V5LTAwMzI=";
    /** HMAC pepper, base64. */
    private String hmacKeyBase64 = "ZGV2LW9ubHktcGFuLWhtYWMtcGVwcGVy";

    public String encrypt(String pan) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BusinessException("PAN_ENCRYPT_FAILED", "Could not protect the PAN: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new BusinessException("PAN_DECRYPT_FAILED", "Could not read the PAN: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public String hash(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(hmacKeyBase64), "HmacSHA256"));
            byte[] h = mac.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("PAN_HASH_FAILED", "Could not hash the PAN", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** A 16-digit PAN: the product's BIN, random digits, the printed last four when given, Luhn-valid. */
    public static String generatePan(String bin, String last4) {
        String prefix = (bin == null || bin.isBlank() ? "453211" : bin.replaceAll("[^0-9]", ""));
        if (prefix.length() > 8) prefix = prefix.substring(0, 8);
        if (last4 != null && last4.matches("[0-9]{4}")) {
            for (int attempt = 0; attempt < 5000; attempt++) {
                StringBuilder sb = new StringBuilder(prefix);
                while (sb.length() < 12) sb.append(RANDOM.nextInt(10));
                String candidate = sb + last4;
                if (luhnOk(candidate)) return candidate;
            }
        }
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < 15) sb.append(RANDOM.nextInt(10));
        return sb + String.valueOf(luhnDigit(sb.toString()));
    }

    public static boolean luhnOk(String pan) {
        int sum = 0; boolean alt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int d = pan.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d; alt = !alt;
        }
        return sum % 10 == 0;
    }

    public static char luhnDigit(String partial) {
        for (char c = '0'; c <= '9'; c++) if (luhnOk(partial + c)) return c;
        return '0';
    }

    public static String mask(String pan) {
        return pan == null || pan.length() < 10 ? "****" : pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
    }

    private byte[] key() {
        byte[] k = Base64.getDecoder().decode(encryptionKeyBase64);
        if (k.length != 32) throw new IllegalStateException("cards.pan.encryption-key-base64 must decode to 32 bytes");
        return k;
    }
}
