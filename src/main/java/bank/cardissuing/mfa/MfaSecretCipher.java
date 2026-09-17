package bank.cardissuing.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Cifra el secreto TOTP en reposo (AES-256-GCM) con una llave dedicada, separada de la del PAN. */
@Component
public class MfaSecretCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public MfaSecretCipher(@Value("${cms.mfa.key-base64:ZGV2LW9ubHktbWZhLXNlY3JldC1rZXktMzJieXRlcyE=}") String keyB64) {
        byte[] k = Base64.getDecoder().decode(keyB64);
        if (k.length != 32) throw new IllegalStateException("cms.mfa.key-base64 debe decodificar a 32 bytes");
        this.key = k;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.US_ASCII));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el secreto MFA", e);
        }
    }

    public String decrypt(String enc) {
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar el secreto MFA", e);
        }
    }
}
