package bank.cardissuing.plastics.application;

import bank.cardissuing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Writes the personalization file and seals it.
 *
 * <p>Format is a plain pipe-delimited text: a header, one REC line per plastic, a
 * trailer with the count. Each REC starts with its line number in the batch and the
 * plastic id, which is what the bureau reports back on; the per-card sequence is
 * informative only. The bureau receives the file encrypted with AES-256-GCM under
 * the shared key (IV per file), plus the SHA-256 of the plaintext to check what it
 * decrypts.
 *
 * <p>The CMS keeps no full PAN by design, so the record carries the BIN, the last four
 * and a masked reference; the bureau's data-prep derives the PAN from BIN + account
 * with the HSM, which also supplied the PVV and CVV2 written here.
 */
@Component
@RequiredArgsConstructor
public class PersoFileBuilder {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlasticSettings settings;

    public record Record(int recordNo, Long plasticId, int sequence, Long cardId, String bin, String last4,
                         String embossedName, LocalDate expiry, String serviceCode, String chipProfile,
                         String pvv, String cvv2, boolean pinMailer, String product) {
        public String panReference() { return bin + "******" + last4; }
    }

    public record Encrypted(byte[] cipherText, String ivBase64) { }

    public String build(String batchNumber, String manufacturer, List<Record> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("HDR|").append(batchNumber).append('|').append(manufacturer).append('|')
          .append(LocalDateTime.now().format(STAMP)).append("|CMS-MEXICO|1.0\n");
        for (Record r : records) {
            sb.append("REC|").append(r.recordNo()).append('|').append(r.plasticId()).append('|')
              .append(r.sequence()).append('|').append(r.cardId()).append('|')
              .append(r.bin()).append('|').append(r.last4()).append('|').append(r.panReference()).append('|')
              .append(clean(r.embossedName())).append('|')
              .append(r.expiry() != null ? r.expiry().format(EXPIRY) : "").append('|')
              .append(r.serviceCode()).append('|').append(r.chipProfile()).append('|')
              .append(r.pvv() != null ? r.pvv() : "").append('|').append(r.cvv2() != null ? r.cvv2() : "").append('|')
              .append(r.pinMailer() ? "Y" : "N").append('|').append(clean(r.product())).append('\n');
        }
        sb.append("TRL|").append(records.size()).append('\n');
        return sb.toString();
    }

    public Encrypted encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new Encrypted(c.doFinal(plain), Base64.getEncoder().encodeToString(iv));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("PERSO_ENCRYPT_FAILED", "Could not encrypt personalization file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public byte[] decrypt(byte[] cipherText, String ivBase64) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(ivBase64)));
            return c.doFinal(cipherText);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("PERSO_DECRYPT_FAILED", "Could not decrypt personalization file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public static String sha256(byte[] content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private SecretKeySpec key() {
        byte[] k = Base64.getDecoder().decode(settings.getManufacturerKeyBase64());
        if (k.length != 32) throw new BusinessException("PERSO_BAD_KEY", "plastics.manufacturer-key-base64 must decode to 32 bytes", HttpStatus.INTERNAL_SERVER_ERROR);
        return new SecretKeySpec(k, "AES");
    }

    /** Embossing lines cannot carry the delimiter or line breaks. */
    private static String clean(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
