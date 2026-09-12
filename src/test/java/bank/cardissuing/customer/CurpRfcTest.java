package bank.cardissuing.customer;

import bank.cardissuing.customer.application.CurpRfc;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The public CURP and RFC algorithms: check digits, structure and consistency with the person. */
class CurpRfcTest {

    // A well-formed CURP for "Maria Gutierrez Lopez", woman, born 1990-05-14 in Jalisco.
    private static String curpOf(String first17) { return first17 + CurpRfc.curpCheckDigit(first17); }
    private static String rfcOf(String first12) { return first12 + CurpRfc.rfcCheckDigit(first12); }

    @Test
    void curpValidaYConsistente() {
        String curp = curpOf("GULM900514MJCTPR0");
        CurpRfc.Check c = CurpRfc.curp(curp, LocalDate.of(1990, 5, 14), "M", "Gutiérrez", "María");
        assertTrue(c.ok(), c.detail());
    }

    @Test
    void curpConDigitoIncorrecto() {
        String curp = curpOf("GULM900514MJCTPR0");
        String bad = curp.substring(0, 17) + (curp.charAt(17) == '9' ? '0' : (char) (curp.charAt(17) + 1));
        assertFalse(CurpRfc.curp(bad, LocalDate.of(1990, 5, 14), "M", "Gutiérrez", "María").ok());
    }

    @Test
    void curpQueNoCoincideConLaPersona() {
        String curp = curpOf("GULM900514MJCTPR0");
        assertFalse(CurpRfc.curp(curp, LocalDate.of(1991, 5, 14), "M", "Gutiérrez", "María").ok(), "fecha distinta");
        assertFalse(CurpRfc.curp(curp, LocalDate.of(1990, 5, 14), "H", "Gutiérrez", "María").ok(), "sexo distinto");
        assertFalse(CurpRfc.curp(curp, LocalDate.of(1990, 5, 14), "M", "Pérez", "María").ok(), "apellido distinto");
        assertFalse(CurpRfc.curp("GULM900514MZZTPR09", LocalDate.of(1990, 5, 14), "M", "Gutiérrez", "María").ok(), "entidad inexistente");
        assertFalse(CurpRfc.curp("mal", null, null, null, null).ok());
    }

    @Test
    void curpDeJoseYMariaUsaElSegundoNombre() {
        // "José Luis Hernández Ruiz": the CURP takes the L of Luis
        String curp = curpOf("HERL850101HDFRZS0");
        assertTrue(CurpRfc.curp(curp, LocalDate.of(1985, 1, 1), "H", "Hernández", "José Luis").ok());
    }

    @Test
    void rfcValidoYConsistente() {
        String rfc = rfcOf("GULM900514AB");
        assertTrue(CurpRfc.rfc(rfc, LocalDate.of(1990, 5, 14), "Gutiérrez").ok());
        assertFalse(CurpRfc.rfc(rfc, LocalDate.of(1990, 5, 15), "Gutiérrez").ok(), "fecha distinta");
        assertFalse(CurpRfc.rfc(rfc, LocalDate.of(1990, 5, 14), "Pérez").ok(), "apellido distinto");
        assertFalse(CurpRfc.rfc("GULM900514ABX", LocalDate.of(1990, 5, 14), "Gutiérrez").ok(), "estructura");
        String bad = rfc.substring(0, 12) + (rfc.charAt(12) == '5' ? '6' : '5');
        assertFalse(CurpRfc.rfc(bad, LocalDate.of(1990, 5, 14), "Gutiérrez").ok(), "dígito verificador");
    }

    @Test
    void rfcConDigitoConocido() {
        // GODE561231GR8 is the SAT's own documented example of a natural-person RFC
        assertEquals('8', CurpRfc.rfcCheckDigit("GODE561231GR"));
        assertTrue(CurpRfc.rfc("GODE561231GR8", LocalDate.of(1956, 12, 31), "Gómez").ok());
    }

    @Test
    void normalizaAcentosYEspacios() {
        assertEquals("MARIA JOSE NUÑEZ", CurpRfc.normalize("  María  José   Núñez "));
    }
}
