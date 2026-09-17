package bank.cardissuing.mfa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {

    // Semilla oficial RFC 6238 (ASCII "12345678901234567890") en Base32.
    private static final String SEED32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238_vectors_6digits() {
        // Los vectores del RFC son de 8 dígitos; los 6 dígitos son sus últimos 6.
        assertEquals("287082", Totp.codeAt(SEED32, 59L));          // 94287082
        assertEquals("081804", Totp.codeAt(SEED32, 1111111109L));  // 07081804
        assertEquals("050471", Totp.codeAt(SEED32, 1111111111L));  // 14050471
        assertEquals("005924", Totp.codeAt(SEED32, 1234567890L));  // 89005924
        assertEquals("279037", Totp.codeAt(SEED32, 2000000000L));  // 69279037
    }

    @Test
    void base32_roundtrip() {
        byte[] data = "hola-mundo-totp!".getBytes();
        assertArrayEquals(data, Totp.base32Decode(Totp.base32Encode(data)));
    }

    @Test
    void verify_acceptsCurrentCode_andRejectsWrongOnesAndBadFormat() {
        String secret = Totp.newSecret();
        long now = System.currentTimeMillis() / 1000L;
        String good = Totp.codeAt(secret, now);
        assertTrue(Totp.verify(secret, good), "el código actual debe verificar");
        assertFalse(Totp.verify(secret, "12345"), "formato inválido");
        assertFalse(Totp.verify(secret, "abcdef"), "formato inválido");
        assertFalse(Totp.verify(secret, null), "nulo");
    }

    @Test
    void newSecret_isValidBase32() {
        String s = Totp.newSecret();
        assertTrue(s.length() >= 16);
        assertDoesNotThrow(() -> Totp.base32Decode(s));
    }
}
