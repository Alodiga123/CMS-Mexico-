package bank.cardissuing.plastics;

import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.plastics.application.PersoFileBuilder;
import bank.cardissuing.plastics.application.PersoFileBuilder.Record;
import bank.cardissuing.plastics.application.PlasticSettings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersoFileBuilderTest {

    PlasticSettings settings = new PlasticSettings();
    PersoFileBuilder builder = new PersoFileBuilder(settings);

    private Record rec(int no, long plasticId, String name) {
        return new Record(no, plasticId, 1, 7L, "453212", "4321", name, LocalDate.of(2028, 6, 30), "201", "LACPI-MX-01", "1234", "567", true, "Prepago MX");
    }

    @Test
    void file_hasHeaderRecordsAndTrailer() {
        String f = builder.build("EMB-20260907-001", "IDEMIA", List.of(rec(1, 10L, "ANA PEREZ"), rec(2, 11L, "LUIS GOMEZ")));
        String[] lines = f.split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].startsWith("HDR|EMB-20260907-001|IDEMIA|"));
        assertEquals("REC|1|10|1|7|453212|4321|453212******4321|ANA PEREZ|06/28|201|LACPI-MX-01|1234|567|Y|Prepago MX", lines[1]);
        assertEquals("TRL|2", lines[3]);
    }

    @Test
    void delimiterAndLineBreaksInNames_areNeutralised() {
        String f = builder.build("B", "M", List.of(rec(1, 10L, "ANA|PEREZ\nX")));
        assertTrue(f.contains("|ANA PEREZ X|"));
    }

    @Test
    void encrypt_thenDecrypt_roundTrips_withFreshIvEachTime() {
        byte[] plain = "HDR|x\nTRL|0\n".getBytes(StandardCharsets.UTF_8);
        PersoFileBuilder.Encrypted a = builder.encrypt(plain);
        PersoFileBuilder.Encrypted b = builder.encrypt(plain);
        assertNotEquals(a.ivBase64(), b.ivBase64());
        assertFalse(java.util.Arrays.equals(a.cipherText(), b.cipherText()));
        assertArrayEquals(plain, builder.decrypt(a.cipherText(), a.ivBase64()));
        assertArrayEquals(plain, builder.decrypt(b.cipherText(), b.ivBase64()));
    }

    @Test
    void tamperedCiphertext_isRejected() {
        PersoFileBuilder.Encrypted a = builder.encrypt("hello".getBytes(StandardCharsets.UTF_8));
        a.cipherText()[0] ^= 1;
        assertEquals("PERSO_DECRYPT_FAILED", assertThrows(BusinessException.class, () -> builder.decrypt(a.cipherText(), a.ivBase64())).getErrorCode());
    }

    @Test
    void badKeyLength_isRefused() {
        settings.setManufacturerKeyBase64("c2hvcnQ="); // "short"
        assertEquals("PERSO_BAD_KEY", assertThrows(BusinessException.class, () -> builder.encrypt(new byte[]{1})).getErrorCode());
    }

    @Test
    void sha256_isStable() {
        assertEquals(PersoFileBuilder.sha256("abc".getBytes(StandardCharsets.UTF_8)), PersoFileBuilder.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PersoFileBuilder.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
