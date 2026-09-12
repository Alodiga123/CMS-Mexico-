package bank.cardissuing.customer.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYC;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.domain.KycDocument;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.customer.infrastructure.KYCRepository;
import bank.cardissuing.customer.infrastructure.KycDocumentRepository;
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
    private final KycDocumentRepository documents;
    private final DocumentVerifierClient verifier;
    private final bank.cardissuing.card.application.PanVault vault;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    /** The pure decision, so it can be tested without a database or a provider. */
    public static Outcome evaluate(Customer c, String documentType, String documentNumber, LocalDate documentExpiresAt,
                                   boolean curpTakenByOther, KycScreeningClient.Result screen) {
        return evaluate(c, documentType, documentNumber, documentExpiresAt, curpTakenByOther, screen, KycDocument.Verification.MATCH, "cotejo no exigido");
    }

    /** Same decision, with the state of the identification image: it must exist and belong to the customer. */
    public static Outcome evaluate(Customer c, String documentType, String documentNumber, LocalDate documentExpiresAt,
                                   boolean curpTakenByOther, KycScreeningClient.Result screen,
                                   KycDocument.Verification docMatch, String docDetail) {
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
        if (docMatch == null) {
            checks.add(new Check("COTEJO", false, "sin imagen de la identificación: cárgala para cotejarla con el titular"));
            review = true;
        } else switch (docMatch) {
            case MATCH -> checks.add(new Check("COTEJO", true, docDetail != null ? docDetail : "la identificación corresponde al titular"));
            case MISMATCH -> { checks.add(new Check("COTEJO", false, docDetail != null ? docDetail : "la identificación no corresponde al titular")); hard = true; }
            case UNREADABLE -> { checks.add(new Check("COTEJO", false, docDetail != null ? docDetail : "la identificación no se pudo leer: revisión del analista")); review = true; }
            default -> { checks.add(new Check("COTEJO", false, docDetail != null ? docDetail : "identificación pendiente de cotejo por un analista")); review = true; }
        }
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
        String risk = (screen != null && screen.hit()) || docMatch == KycDocument.Verification.MISMATCH ? "HIGH" : (c.isPep() || review || hard) ? "MEDIUM" : "LOW";
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
        KycDocument front = documents.findFirstByCustomerAndSideOrderByUploadedAtDesc(c, KycDocument.Side.FRONT).orElse(null);
        Outcome o = evaluate(c, kyc.getDocumentType(), kyc.getDocumentNumber(), kyc.getExpiresAt() != null ? kyc.getExpiresAt().toLocalDate() : null, taken, screen,
                front != null ? front.getVerification() : null, front != null ? front.getVerificationDetail() : null);
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
        if (approve && "HIGH".equals(kyc.getRiskLevel())) throw new BusinessException("KYC_LIST_HIT", "No se puede aprobar un cliente con coincidencia en listas restringidas o cuya identificación es de otra persona", HttpStatus.CONFLICT);
        if (approve && documents.findFirstByCustomerAndSideOrderByUploadedAtDesc(c, KycDocument.Side.FRONT).isEmpty()) throw new BusinessException("KYC_DOCUMENT_REQUIRED", "No se puede aprobar sin la imagen de la identificación", HttpStatus.CONFLICT);
        kyc.setStatus(approve ? KYCStatus.VERIFIED : KYCStatus.REJECTED);
        kyc.setVerifiedAt(approve ? LocalDateTime.now() : null);
        kyc.setReviewNote(note.trim());
        kyc.setReviewedBy(by);
        kyc.setReviewedAt(LocalDateTime.now());
        kycRepository.save(kyc);
        audit.log(approve ? "KYC_APPROVED" : "KYC_REJECTED_BY_ANALYST", "Customer", String.valueOf(c.getId()), by);
        return kyc;
    }

    // ------------------------------------------------------------------ identification documents

    /** Stores the image encrypted, has it read (provider or simulated) and compares it with the customer; then re-runs the file. */
    @Transactional
    public KycDocument attachDocument(Long customerId, KycDocument.Side side, String documentType, String fileName, String contentType, byte[] content, String by) {
        Customer c = customerRepository.findById(customerId).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Cliente " + customerId + " no encontrado", HttpStatus.NOT_FOUND));
        if (content == null || content.length == 0) throw new BusinessException("KYC_DOCUMENT_EMPTY", "El archivo está vacío", HttpStatus.BAD_REQUEST);
        if (content.length > verifier.getMaxBytes()) throw new BusinessException("KYC_DOCUMENT_TOO_LARGE", "El archivo supera " + (verifier.getMaxBytes() / 1024 / 1024) + " MB", HttpStatus.BAD_REQUEST);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!(ct.startsWith("image/") || ct.equals("application/pdf"))) throw new BusinessException("KYC_DOCUMENT_TYPE", "Solo se admiten imágenes (JPG, PNG) o PDF", HttpStatus.BAD_REQUEST);
        KYC kyc = kycRepository.findByCustomer(c).orElseGet(() -> { KYC k = new KYC(); k.setCustomer(c); k.setStatus(KYCStatus.PENDING); return kycRepository.save(k); });
        KycDocument d = new KycDocument();
        d.setCustomer(c);
        d.setSide(side != null ? side : KycDocument.Side.FRONT);
        d.setDocumentType(documentType != null ? documentType.toUpperCase(Locale.ROOT) : kyc.getDocumentType());
        d.setFileName(fileName);
        d.setContentType(contentType);
        d.setSizeBytes(content.length);
        d.setSha256(sha256(content));
        d.setContentEncrypted(vault.encrypt(java.util.Base64.getEncoder().encodeToString(content)));
        d.setUploadedAt(LocalDateTime.now());
        d.setUploadedBy(by);
        if (d.getSide() == KycDocument.Side.FRONT) {
            DocumentVerifierClient.KycSubject subject = new DocumentVerifierClient.KycSubject(c.getFullName(), c.getCurp(),
                    c.getBirthDate() != null ? c.getBirthDate().toString() : null, kyc.getDocumentNumber(), kyc.getExpiresAt() != null ? kyc.getExpiresAt().toLocalDate().toString() : null);
            DocumentVerifierClient.Extraction x = verifier.read(d.getDocumentType(), fileName, contentType, content, subject);
            compare(d, x, subject);
        } else {
            d.setVerification(KycDocument.Verification.MATCH);
            d.setVerificationDetail("reverso archivado");
        }
        d = documents.save(d);
        audit.log("KYC_DOCUMENT_" + d.getVerification().name(), "Customer", String.valueOf(c.getId()), by);
        if (d.getSide() == KycDocument.Side.FRONT && kyc.getDocumentType() != null) run(kyc, by);
        return d;
    }

    /** Field by field: what the provider read against what the CMS captured. */
    static void compare(KycDocument d, DocumentVerifierClient.Extraction x, DocumentVerifierClient.KycSubject s) {
        try { d.setExtractedJson(new ObjectMapper().writeValueAsString(x)); } catch (Exception ignored) { }
        if (!x.available()) { d.setVerification(KycDocument.Verification.PENDING_MANUAL); d.setVerificationDetail(x.detail()); return; }
        if (!x.readable()) { d.setVerification(KycDocument.Verification.UNREADABLE); d.setVerificationDetail(x.detail()); return; }
        List<String> bad = new ArrayList<>();
        boolean curpRead = x.curp() != null && !x.curp().isBlank();
        if (curpRead && s.curp() != null && !x.curp().trim().equalsIgnoreCase(s.curp().trim())) bad.add("CURP");
        if (x.fullName() != null && s.fullName() != null && nameSimilarity(x.fullName(), s.fullName()) < 0.6) bad.add("nombre");
        if (x.birthDate() != null && s.birthDate() != null && !x.birthDate().trim().equals(s.birthDate().trim())) bad.add("fecha de nacimiento");
        if (x.documentNumber() != null && s.documentNumber() != null && !digitsAndLetters(x.documentNumber()).equals(digitsAndLetters(s.documentNumber()))) bad.add("número de identificación");
        boolean expired = false;
        if (x.expiresAt() != null && !x.expiresAt().isBlank()) {
            try { expired = LocalDate.parse(x.expiresAt().trim()).isBefore(LocalDate.now()); } catch (Exception ignored) { }
        }
        // identity decides the match; an expired document is the DOCUMENTO check's business, not a different person
        if (bad.isEmpty()) { d.setVerification(KycDocument.Verification.MATCH); d.setVerificationDetail("la identificación corresponde al titular (" + x.detail() + ")" + (expired ? "; la vigencia leída está vencida" : "")); }
        else { d.setVerification(KycDocument.Verification.MISMATCH); d.setVerificationDetail("la identificación no coincide en: " + String.join(", ", bad) + " (" + x.detail() + ")"); }
    }

    /** Share of the captured name's words found in the document's name. */
    static double nameSimilarity(String read, String captured) {
        String[] want = CurpRfc.normalize(captured).split(" ");
        Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(CurpRfc.normalize(read).split(" ")));
        if (want.length == 0) return 0;
        int hit = 0;
        for (String w : want) if (!w.isEmpty() && have.contains(w)) hit++;
        return (double) hit / want.length;
    }

    private static String digitsAndLetters(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }

    private static String sha256(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    /** An analyst who looked at the image says whether it is the customer's. */
    @Transactional
    public KycDocument decideDocument(Long customerId, Long documentId, boolean matches, String note, String by) {
        Customer c = customerRepository.findById(customerId).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Cliente " + customerId + " no encontrado", HttpStatus.NOT_FOUND));
        KycDocument d = documents.findById(documentId).filter(x -> x.getCustomer().getId().equals(c.getId()))
                .orElseThrow(() -> new BusinessException("KYC_DOCUMENT_NOT_FOUND", "Documento " + documentId + " no encontrado", HttpStatus.NOT_FOUND));
        if (note == null || note.trim().length() < 5) throw new BusinessException("KYC_NOTE_REQUIRED", "Escribe el motivo del cotejo", HttpStatus.BAD_REQUEST);
        d.setVerification(matches ? KycDocument.Verification.MATCH : KycDocument.Verification.MISMATCH);
        d.setVerificationDetail((matches ? "cotejo manual: corresponde al titular. " : "cotejo manual: no corresponde al titular. ") + note.trim());
        d.setVerifiedBy(by);
        d.setVerifiedAt(LocalDateTime.now());
        documents.save(d);
        audit.log(matches ? "KYC_DOCUMENT_MATCH" : "KYC_DOCUMENT_MISMATCH", "Customer", String.valueOf(c.getId()), by);
        kycRepository.findByCustomer(c).ifPresent(k -> { if (k.getDocumentType() != null) run(k, by); });
        return d;
    }

    public List<KycDocument> documentsOf(Customer c) { return documents.findByCustomerOrderByUploadedAtDesc(c); }

    public byte[] content(KycDocument d) {
        return java.util.Base64.getDecoder().decode(vault.decrypt(d.getContentEncrypted()));
    }

    public Map<String, Object> documentView(KycDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("side", d.getSide().name());
        m.put("documentType", d.getDocumentType());
        m.put("fileName", d.getFileName());
        m.put("contentType", d.getContentType());
        m.put("sizeBytes", d.getSizeBytes());
        m.put("sha256", d.getSha256());
        m.put("uploadedAt", d.getUploadedAt());
        m.put("uploadedBy", d.getUploadedBy());
        m.put("verification", d.getVerification() != null ? d.getVerification().name() : null);
        m.put("verificationDetail", d.getVerificationDetail());
        m.put("verifiedBy", d.getVerifiedBy());
        m.put("verifiedAt", d.getVerifiedAt());
        try { m.put("extracted", d.getExtractedJson() != null ? json.readTree(d.getExtractedJson()) : null); } catch (Exception e) { m.put("extracted", null); }
        return m;
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
        m.put("documents", kyc.getCustomer() != null ? documentsOf(kyc.getCustomer()).stream().map(this::documentView).toList() : List.of());
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
