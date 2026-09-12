package bank.cardissuing.customer;

import bank.cardissuing.customer.application.CurpRfc;
import bank.cardissuing.customer.application.DocumentVerifierClient;
import bank.cardissuing.customer.application.KycScreeningClient;
import bank.cardissuing.customer.application.KycService;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.domain.KycDocument;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The identification image must exist and belong to the customer before the KYC is verified. */
class KycDocumentTest {

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

    private static KycService.Outcome eval(KycDocument.Verification v) {
        return KycService.evaluate(maria(), "INE", "IDMEX1234567890", DOC_OK, false, CLEAN, v, null);
    }

    @Test
    void sinImagenNoSeVerifica() {
        KycService.Outcome o = eval(null);
        assertEquals(KYCStatus.REVIEW, o.status());
        assertTrue(o.checks().stream().anyMatch(c -> c.code().equals("COTEJO") && !c.ok()));
    }

    @Test
    void imagenCotejadaVerifica() {
        assertEquals(KYCStatus.VERIFIED, eval(KycDocument.Verification.MATCH).status());
        assertEquals(9, eval(KycDocument.Verification.MATCH).checks().size());
    }

    @Test
    void imagenDeOtraPersonaRechazaConRiesgoAlto() {
        KycService.Outcome o = eval(KycDocument.Verification.MISMATCH);
        assertEquals(KYCStatus.REJECTED, o.status());
        assertEquals("HIGH", o.riskLevel());
    }

    @Test
    void ilegibleOPendienteVanAlAnalista() {
        assertEquals(KYCStatus.REVIEW, eval(KycDocument.Verification.UNREADABLE).status());
        assertEquals(KYCStatus.REVIEW, eval(KycDocument.Verification.PENDING_MANUAL).status());
    }

    @Test
    void comparacionCampoPorCampo() throws Exception {
        Method compare = KycService.class.getDeclaredMethod("compare", KycDocument.class, DocumentVerifierClient.Extraction.class, DocumentVerifierClient.KycSubject.class);
        compare.setAccessible(true);
        DocumentVerifierClient.KycSubject s = new DocumentVerifierClient.KycSubject("María Gutiérrez López", "GULM900514MJCTPR00", "1990-05-14", "IDMEX1234567890", "2028-01-01");

        KycDocument d = new KycDocument();
        compare.invoke(null, d, new DocumentVerifierClient.Extraction(true, true, "MARIA GUTIERREZ LOPEZ", "GULM900514MJCTPR00", "1990-05-14", "IDMEX 1234567890", "2028-01-01", "ocr"), s);
        assertEquals(KycDocument.Verification.MATCH, d.getVerification(), d.getVerificationDetail());

        d = new KycDocument();
        compare.invoke(null, d, new DocumentVerifierClient.Extraction(true, true, "JUAN PEREZ", "PEXJ800101HDFRXN01", "1980-01-01", "IDMEX0000000001", "2028-01-01", "ocr"), s);
        assertEquals(KycDocument.Verification.MISMATCH, d.getVerification());
        assertTrue(d.getVerificationDetail().contains("CURP") && d.getVerificationDetail().contains("nombre"), d.getVerificationDetail());

        d = new KycDocument();
        compare.invoke(null, d, new DocumentVerifierClient.Extraction(true, true, "MARIA GUTIERREZ LOPEZ", "GULM900514MJCTPR00", "1990-05-14", "IDMEX1234567890", "2020-01-01", "ocr"), s);
        assertEquals(KycDocument.Verification.MATCH, d.getVerification(), "misma persona: la vigencia vencida la decide la comprobación DOCUMENTO");
        assertTrue(d.getVerificationDetail().contains("vencida"));

        d = new KycDocument();
        compare.invoke(null, d, new DocumentVerifierClient.Extraction(true, false, null, null, null, null, null, "borrosa"), s);
        assertEquals(KycDocument.Verification.UNREADABLE, d.getVerification());

        d = new KycDocument();
        compare.invoke(null, d, new DocumentVerifierClient.Extraction(false, false, null, null, null, null, null, "manual"), s);
        assertEquals(KycDocument.Verification.PENDING_MANUAL, d.getVerification());
    }

    @Test
    void modoSimuladoLeeSegunElNombreDelArchivo() {
        DocumentVerifierClient v = new DocumentVerifierClient(); v.setMode("simulated");
        DocumentVerifierClient.KycSubject s = new DocumentVerifierClient.KycSubject("María Gutiérrez López", "GULM900514MJCTPR00", "1990-05-14", "IDMEX1234567890", "2028-01-01");
        assertEquals("GULM900514MJCTPR00", v.read("INE", "ine_frente.png", "image/png", new byte[10], s).curp());
        assertNotEquals("GULM900514MJCTPR00", v.read("INE", "ine_mal.png", "image/png", new byte[10], s).curp());
        assertFalse(v.read("INE", "ine_ilegible.png", "image/png", new byte[10], s).readable());
        v.setMode("manual");
        assertFalse(v.read("INE", "ine_frente.png", "image/png", new byte[10], s).available());
    }
}
