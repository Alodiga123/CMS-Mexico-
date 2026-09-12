package bank.cardissuing.customer.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYC;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.customer.infrastructure.KYCRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Know-your-customer for a cardholder. Every registration runs the same checks and leaves the
 * evidence on the KYC record: identity (CURP and RFC structure, check digits and consistency with
 * the name and birth date), eligibility (adult, identification document in force), uniqueness
 * (one customer per CURP) and screening against restricted lists. The outcome decides what the
 * business may do next: VERIFIED issues cards, REVIEW waits for an analyst (politically exposed
 * person, provider down), REJECTED never gets a card until the data is corrected and re-run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycService {

    public static final Set<String> DOCUMENT_TYPES = Set.of("INE", "PASAPORTE", "CEDULA_PROFESIONAL", "FM2_FM3", "MATRICULA_CONSULAR");

    public record Check(String code, boolean ok, String detail) { }
    public record Outcome(KYCStatus status, String riskLevel, List<Check> checks) { }

    private final KYCRepository kycRepository;
    private final CustomerRepository customerRepository;
    private final KycScreeningClient screening;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    /** The pure decision, so it can be tested without a database or a provider. */
    public static Outcome evaluate(Customer c, String documentType, String documentNumber, LocalDate documentExpiresAt,
                                   boolean curpTakenByOther, KycScreeningClient.Result screen) {
        List<Check> checks = new ArrayList<>();
        boolean hard = false;

        CurpRfc.Check curp = CurpRfc.curp(c.getCurp(), c.getBirthDate(), c.getSex(), c.getPaternalSurname(), c.getFirstNames());
        checks.add(new Check("CURP", curp.ok(), curp.detail())); hard |= !curp.ok();
        CurpRfc.Check rfc = CurpRfc.rfc(c.getRfc(), c.getBirthDate(), c.getPaternalSurname());
        checks.add(new Check("RFC", rfc.ok(), rfc.detail())); hard |= !rfc.ok();

        boolean adult = c.getBirthDate() != null && Period.between(c.getBirthDate(), LocalDate.now()).getYears() >= 18;
        checks.add(new Check("EDAD", adult, adult ? "mayor de edad" : "el titular debe ser mayor de 18 años")); hard |= !adult;

        boolean docType = documentType != null && DOCUMENT_TYPES.contains(documentType.toUpperCase(Locale.ROOT));
        boolean docNumber = documentNumber != null && documentNumber.replaceAll("[^A-Za-z0-9]", "").length() >= 8;
        boolean docValid = documentExpiresAt != null && !documentExpiresAt.isBefore(LocalDate.now());
        boolean doc = docType && docNumber && docValid;
        checks.add(new Check("DOCUMENTO", doc, !docType ? "tipo de identificación no admitido" : !docNumber ? "número de identificación incompleto"
                : !docValid ? "la identificación está vencida o sin vigencia" : documentType.toUpperCase(Locale.ROOT) + " vigente hasta " + documentExpiresAt));
        hard |= !doc;

        checks.add(new Check("UNICIDAD", !curpTakenByOther, curpTakenByOther ? "la CURP ya está registrada en otro cliente" : "CURP única en el CMS"));
        hard |= curpTakenByOther;

        boolean contact = c.getEmail() != null && c.getEmail().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") && c.getPhoneNumber() != null && c.getPhoneNumber().replaceAll("\\D", "").length() >= 10;
        checks.add(new Check("CONTACTO", contact, contact ? "correo y teléfono de contacto válidos" : "correo o teléfono de contacto inválidos"));
        hard |= !contact;

        boolean review = false;
        if (screen == null || !screen.available()) {
            checks.add(new Check("LISTAS", false, screen != null ? screen.detail() : "listas no consultadas"));
            review = true;
        } else if (screen.hit()) {
            checks.add(new Check("LISTAS", false, "coincidencia en " + String.join(", ", screen.lists()) + " (" + screen.detail() + ")"));
            hard = true;
        } else {
            checks.add(new Check("LISTAS", true, screen.detail()));
        }
        if (c.isPep()) {
            checks.add(new Check("PEP", false, "persona políticamente expuesta declarada: requiere aprobación de un analista"));
            review = true;
        } else {
            checks.add(new Check("PEP", true, "no es persona políticamente expuesta (declaración del titular)"));
        }

        KYCStatus status = hard ? KYCStatus.REJECTED : review ? KYCStatus.REVIEW : KYCStatus.VERIFIED;
        String risk = (screen != null && screen.hit()) ? "HIGH" : (c.isPep() || review || hard) ? "MEDIUM" : "LOW";
        return new Outcome(status, risk, checks);
    }

    @Transactional
    public KYC register(Customer customer, String documentType, String documentNumber, LocalDate documentExpiresAt, String by) {
        KYC kyc = kycRepository.findByCustomer(customer).orElseGet(KYC::new);
        kyc.setCustomer(customer);
        kyc.setDocumentType(documentType != null ? documentType.toUpperCase(Locale.ROOT) : null);
        kyc.setDocumentNumber(documentNumber);
        kyc.setExpiresAt(documentExpiresAt != null ? documentExpiresAt.atStartOfDay() : null);
        run(kyc, by);
        return kyc;
    }

    @Transactional
    public KYC rerun(Long customerId, String by) {
        Customer c = customerRepository.findById(customerId).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Cliente " + customerId + " no encontrado", HttpStatus.NOT_FOUND));
        KYC kyc = kycRepository.findByCustomer(c).orElseThrow(() -> new BusinessException("KYC_NOT_FOUND", "El cliente no tiene expediente KYC", HttpStatus.NOT_FOUND));
        run(kyc, by);
        return kyc;
    }

    private void run(KYC kyc, String by) {
        Customer c = kyc.getCustomer();
        boolean taken = c.getCurp() != null && customerRepository.findByCurpIgnoreCase(c.getCurp()).stream().anyMatch(o -> !o.getId().equals(c.getId()));
        KycScreeningClient.Result screen = screening.screen(c.getFullName(), c.getCurp(), c.getRfc(), c.getBirthDate());
        Outcome o = evaluate(c, kyc.getDocumentType(), kyc.getDocumentNumber(), kyc.getExpiresAt() != null ? kyc.getExpiresAt().toLocalDate() : null, taken, screen);
        kyc.setStatus(o.status());
        kyc.setRiskLevel(o.riskLevel());
        kyc.setChecksJson(write(o.checks()));
        kyc.setScreenedAt(LocalDateTime.now());
        kyc.setVerifiedAt(o.status() == KYCStatus.VERIFIED ? LocalDateTime.now() : null);
        kyc.setReviewNote(null); kyc.setReviewedBy(null); kyc.setReviewedAt(null);
        kycRepository.save(kyc);
        audit.log("KYC_" + o.status().name(), "Customer", String.valueOf(c.getId()), by);
        log.info("KYC customer {} -> {} ({}): {}", c.getId(), o.status(), o.riskLevel(), o.checks().stream().filter(ch -> !ch.ok()).map(Check::code).toList());
    }

    /** An analyst closes a REVIEW case (or overturns a rejection with a written reason). */
    @Transactional
    public KYC review(Long customerId, boolean approve, String note, String by) {
        Customer c = customerRepository.findById(customerId).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Cliente " + customerId + " no encontrado", HttpStatus.NOT_FOUND));
        KYC kyc = kycRepository.findByCustomer(c).orElseThrow(() -> new BusinessException("KYC_NOT_FOUND", "El cliente no tiene expediente KYC", HttpStatus.NOT_FOUND));
        if (note == null || note.trim().length() < 5) throw new BusinessException("KYC_NOTE_REQUIRED", "Escribe el motivo de la decisión", HttpStatus.BAD_REQUEST);
        if (kyc.getStatus() == KYCStatus.VERIFIED && approve) throw new BusinessException("KYC_ALREADY_VERIFIED", "El cliente ya está verificado", HttpStatus.CONFLICT);
        if (approve && "HIGH".equals(kyc.getRiskLevel())) throw new BusinessException("KYC_LIST_HIT", "No se puede aprobar un cliente con coincidencia en listas restringidas", HttpStatus.CONFLICT);
        kyc.setStatus(approve ? KYCStatus.VERIFIED : KYCStatus.REJECTED);
        kyc.setVerifiedAt(approve ? LocalDateTime.now() : null);
        kyc.setReviewNote(note.trim());
        kyc.setReviewedBy(by);
        kyc.setReviewedAt(LocalDateTime.now());
        kycRepository.save(kyc);
        audit.log(approve ? "KYC_APPROVED" : "KYC_REJECTED_BY_ANALYST", "Customer", String.valueOf(c.getId()), by);
        return kyc;
    }

    public Optional<KYC> of(Customer c) { return kycRepository.findByCustomer(c); }

    public List<Check> checks(KYC kyc) {
        if (kyc == null || kyc.getChecksJson() == null || kyc.getChecksJson().isBlank()) return List.of();
        try { return json.readValue(kyc.getChecksJson(), new TypeReference<List<Check>>() { }); } catch (Exception e) { return List.of(); }
    }

    public Map<String, Object> view(KYC kyc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kyc == null) { m.put("status", "PENDING"); m.put("checks", List.of()); return m; }
        m.put("status", kyc.getStatus() != null ? kyc.getStatus().name() : "PENDING");
        m.put("riskLevel", kyc.getRiskLevel());
        m.put("documentType", kyc.getDocumentType());
        m.put("documentNumber", mask(kyc.getDocumentNumber()));
        m.put("documentExpiresAt", kyc.getExpiresAt() != null ? kyc.getExpiresAt().toLocalDate().toString() : null);
        m.put("screenedAt", kyc.getScreenedAt());
        m.put("verifiedAt", kyc.getVerifiedAt());
        m.put("reviewNote", kyc.getReviewNote());
        m.put("reviewedBy", kyc.getReviewedBy());
        m.put("reviewedAt", kyc.getReviewedAt());
        m.put("checks", checks(kyc));
        return m;
    }

    public static String mask(String s) {
        if (s == null || s.length() < 5) return s;
        return "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    private String write(List<Check> checks) {
        try { return json.writeValueAsString(checks); } catch (Exception e) { return "[]"; }
    }
}
