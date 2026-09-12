package bank.cardissuing.customer;

import bank.cardissuing.customer.application.CurpRfc;
import bank.cardissuing.customer.application.KycScreeningClient;
import bank.cardissuing.customer.application.KycService;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYCStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The KYC decision: what verifies, what goes to an analyst and what is rejected, and why. */
class KycServiceTest {

    private static Customer maria() {
        Customer c = new Customer();
        c.setFirstNames("María"); c.setPaternalSurname("Gutiérrez"); c.setMaternalSurname("López");
        c.setFullName("María Gutiérrez López");
        c.setBirthDate(LocalDate.of(1990, 5, 14)); c.setSex("M");
        c.setCurp("GULM900514MJCTPR0" + CurpRfc.curpCheckDigit("GULM900514MJCTPR0"));
        c.setRfc("GULM900514AB" + CurpRfc.rfcCheckDigit("GULM900514AB"));
        c.setEmail("maria@example.com"); c.setPhoneNumber("5512345678");
        return c;
    }

    private static final KycScreeningClient.Result CLEAN = new KycScreeningClient.Result(true, false, List.of(), 0, "sin coincidencias");
    private static final LocalDate DOC_OK = LocalDate.now().plusYears(2);

    private static String failed(KycService.Outcome o) {
        return String.join(",", o.checks().stream().filter(c -> !c.ok()).map(KycService.Check::code).toList());
    }

    @Test
    void titularLimpioQuedaVerificado() {
        KycService.Outcome o = KycService.evaluate(maria(), "INE", "IDMEX1234567890", DOC_OK, false, CLEAN);
        assertEquals(KYCStatus.VERIFIED, o.status(), failed(o));
        assertEquals("LOW", o.riskLevel());
        assertEquals(9, o.checks().size());
    }

    @Test
    void coincidenciaEnListasRechaza() {
        KycScreeningClient.Result hit = new KycScreeningClient.Result(true, true, List.of("OFAC-SDN"), 95, "coincidencia");
        KycService.Outcome o = KycService.evaluate(maria(), "INE", "IDMEX1234567890", DOC_OK, false, hit);
        assertEquals(KYCStatus.REJECTED, o.status());
        assertEquals("HIGH", o.riskLevel());
        assertEquals("LISTAS", failed(o));
    }

    @Test
    void personaPoliticamenteExpuestaVaARevision() {
        Customer c = maria(); c.setPep(true);
        KycService.Outcome o = KycService.evaluate(c, "PASAPORTE", "G12345678", DOC_OK, false, CLEAN);
        assertEquals(KYCStatus.REVIEW, o.status());
        assertEquals("MEDIUM", o.riskLevel());
        assertEquals("PEP", failed(o));
    }

    @Test
    void proveedorDeListasCaidoVaARevisionNoAprueba() {
        KycScreeningClient.Result down = new KycScreeningClient.Result(false, false, List.of(), 0, "proveedor sin respuesta");
        KycService.Outcome o = KycService.evaluate(maria(), "INE", "IDMEX1234567890", DOC_OK, false, down);
        assertEquals(KYCStatus.REVIEW, o.status());
        assertEquals("LISTAS", failed(o));
    }

    @Test
    void datosInvalidosRechazan() {
        Customer menor = maria(); menor.setBirthDate(LocalDate.now().minusYears(17));
        assertEquals(KYCStatus.REJECTED, KycService.evaluate(menor, "INE", "IDMEX1234567890", DOC_OK, false, CLEAN).status());
        assertTrue(failed(KycService.evaluate(menor, "INE", "IDMEX1234567890", DOC_OK, false, CLEAN)).contains("EDAD"));

        assertTrue(failed(KycService.evaluate(maria(), "INE", "IDMEX1234567890", LocalDate.now().minusDays(1), false, CLEAN)).contains("DOCUMENTO"), "documento vencido");
        assertTrue(failed(KycService.evaluate(maria(), "LICENCIA", "IDMEX1234567890", DOC_OK, false, CLEAN)).contains("DOCUMENTO"), "tipo no admitido");
        assertTrue(failed(KycService.evaluate(maria(), "INE", "IDMEX1234567890", DOC_OK, true, CLEAN)).contains("UNICIDAD"), "CURP repetida");

        Customer curpMala = maria(); curpMala.setCurp("GULM900514MJCTPR00");
        assertTrue(failed(KycService.evaluate(curpMala, "INE", "IDMEX1234567890", DOC_OK, false, CLEAN)).contains("CURP"));
        Customer sinCorreo = maria(); sinCorreo.setEmail("no-es-correo");
        assertTrue(failed(KycService.evaluate(sinCorreo, "INE", "IDMEX1234567890", DOC_OK, false, CLEAN)).contains("CONTACTO"));
    }

    @Test
    void pepConCoincidenciaEnListasEsRechazoNoRevision() {
        Customer c = maria(); c.setPep(true);
        KycScreeningClient.Result hit = new KycScreeningClient.Result(true, true, List.of("PEP nacional"), 80, "coincidencia");
        assertEquals(KYCStatus.REJECTED, KycService.evaluate(c, "INE", "IDMEX1234567890", DOC_OK, false, hit).status());
    }
}
