package bank.cardissuing.card;

import bank.cardissuing.card.application.PanVault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanVaultTest {

    PanVault vault = new PanVault();

    @Test
    void generatedPans_are16Digits_LuhnValid_withBinAndLast4() {
        for (int i = 0; i < 50; i++) {
            String pan = PanVault.generatePan("453212", "9043");
            assertEquals(16, pan.length());
            assertTrue(pan.startsWith("453212"));
            assertTrue(pan.endsWith("9043"));
            assertTrue(PanVault.luhnOk(pan), pan);
        }
        String free = PanVault.generatePan("510000", null);
        assertEquals(16, free.length());
        assertTrue(PanVault.luhnOk(free));
        assertTrue(PanVault.luhnOk("4532015112830366"));
        assertFalse(PanVault.luhnOk("4532015112830367"));
    }

    @Test
    void encrypt_isRandomised_andDecryptsBack() {
        String a = vault.encrypt("4532129876543210");
        String b = vault.encrypt("4532129876543210");
        assertNotEquals(a, b);
        assertEquals("4532129876543210", vault.decrypt(a));
        assertEquals("4532129876543210", vault.decrypt(b));
    }

    @Test
    void hash_isStable_andDiffersPerPan() {
        assertEquals(vault.hash("4532129876543210"), vault.hash("4532129876543210"));
        assertNotEquals(vault.hash("4532129876543210"), vault.hash("4532129876543228"));
        assertEquals(64, vault.hash("4532129876543210").length());
        assertEquals("453212******3210", PanVault.mask("4532129876543210"));
    }
}
