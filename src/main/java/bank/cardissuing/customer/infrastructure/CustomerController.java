package bank.cardissuing.customer.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.CmsPrincipal;
import bank.cardissuing.customer.application.KycService;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYC;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.infrastructure.dto.CustomerCreateRequest;
import bank.cardissuing.customer.infrastructure.dto.CustomerResponse;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cardholders and their KYC. Registration runs the checks at once; cards are issued elsewhere
 * (/api/cards/issue) and only to VERIFIED customers.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Slf4j
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final KYCRepository kycRepository;
    private final CardRepository cardRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final KycService kyc;

    @PostMapping
    @Transactional
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerCreateRequest r) {
        Customer c = new Customer();
        apply(c, r);
        if (c.getFullName() == null || c.getFullName().isBlank()) throw new BusinessException("CUSTOMER_NAME_REQUIRED", "Nombre y apellido paterno son obligatorios", HttpStatus.BAD_REQUEST);
        c = customerRepository.save(c);
        KYC k;
        if (r.hasIdentity()) {
            k = kyc.register(c, r.getDocumentType(), r.getDocumentNumber(), r.getDocumentExpiresAt(), CmsPrincipal.auditName(r.getBy()));
        } else {
            // no identity captured: the file exists, nothing is verified, no card can be issued
            k = new KYC();
            k.setCustomer(c);
            k.setStatus(KYCStatus.PENDING);
            k = kycRepository.save(k);
        }
        log.info("Customer {} registered, KYC {}", c.getId(), k.getStatus());
        return ResponseEntity.ok(view(c, k));
    }

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> getAllCustomers() {
        return ResponseEntity.ok(customerRepository.findAll().stream().map(c -> view(c, kycRepository.findByCustomer(c).orElse(null))).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> one(@PathVariable Long id) {
        Customer c = find(id);
        return ResponseEntity.ok(view(c, kycRepository.findByCustomer(c).orElse(null)));
    }

    /** The whole KYC file: data captured, every check of the last run and the analyst's decision. */
    @GetMapping("/{id}/kyc")
    public ResponseEntity<Map<String, Object>> kycFile(@PathVariable Long id) {
        Customer c = find(id);
        KYC k = kycRepository.findByCustomer(c).orElse(null);
        Map<String, Object> m = new java.util.LinkedHashMap<>(kyc.view(k));
        m.put("customerId", c.getId());
        m.put("fullName", c.getFullName());
        m.put("curp", c.getCurp());
        m.put("rfc", c.getRfc());
        m.put("birthDate", c.getBirthDate());
        m.put("sex", c.getSex());
        m.put("email", c.getEmail());
        m.put("phoneNumber", c.getPhoneNumber());
        m.put("nationality", c.getNationality());
        m.put("occupation", c.getOccupation());
        m.put("address", c.getAddressLine());
        m.put("postalCode", c.getPostalCode());
        m.put("state", c.getState());
        m.put("pep", c.isPep());
        return ResponseEntity.ok(m);
    }

    public record ReviewBody(String decision, String note, String by) { }

    /** An analyst approves or rejects a case under review. */
    @PostMapping("/{id}/kyc/review")
    public ResponseEntity<CustomerResponse> review(@PathVariable Long id, @RequestBody ReviewBody b) {
        if (b == null || b.decision() == null) throw new BusinessException("KYC_DECISION_REQUIRED", "decision debe ser APPROVE o REJECT", HttpStatus.BAD_REQUEST);
        boolean approve = switch (b.decision().toUpperCase(Locale.ROOT)) {
            case "APPROVE", "VERIFY" -> true;
            case "REJECT" -> false;
            default -> throw new BusinessException("KYC_DECISION_REQUIRED", "decision debe ser APPROVE o REJECT", HttpStatus.BAD_REQUEST);
        };
        KYC k = kyc.review(id, approve, b.note(), CmsPrincipal.auditName(b.by()));
        return ResponseEntity.ok(view(find(id), k));
    }

    /** Runs the checks again (after correcting data or when the list provider is back). */
    @PostMapping("/{id}/kyc/rerun")
    public ResponseEntity<CustomerResponse> rerun(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        KYC k = kyc.rerun(id, CmsPrincipal.auditName(body != null ? body.get("by") : null));
        return ResponseEntity.ok(view(find(id), k));
    }

    /** Corrects the identity data and re-runs the checks. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CustomerResponse> update(@PathVariable Long id, @RequestBody CustomerCreateRequest r) {
        Customer c = find(id);
        apply(c, r);
        c = customerRepository.save(c);
        KYC k = kycRepository.findByCustomer(c).orElse(null);
        if (r.hasIdentity()) {
            k = kyc.register(c, r.getDocumentType() != null ? r.getDocumentType() : (k != null ? k.getDocumentType() : null),
                    r.getDocumentNumber() != null ? r.getDocumentNumber() : (k != null ? k.getDocumentNumber() : null),
                    r.getDocumentExpiresAt() != null ? r.getDocumentExpiresAt() : (k != null && k.getExpiresAt() != null ? k.getExpiresAt().toLocalDate() : null),
                    CmsPrincipal.auditName(r.getBy()));
        }
        return ResponseEntity.ok(view(c, k));
    }

    // ------------------------------------------------------------------ helpers

    private Customer find(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Cliente " + id + " no encontrado", HttpStatus.NOT_FOUND));
    }

    private static void apply(Customer c, CustomerCreateRequest r) {
        if (r.getFirstNames() != null) c.setFirstNames(r.getFirstNames().trim());
        if (r.getPaternalSurname() != null) c.setPaternalSurname(r.getPaternalSurname().trim());
        if (r.getMaternalSurname() != null) c.setMaternalSurname(r.getMaternalSurname().trim());
        if (r.getFullName() != null && !r.getFullName().isBlank()) {
            c.setFullName(r.getFullName().trim());
        } else if (c.getFirstNames() != null && c.getPaternalSurname() != null && !c.getFirstNames().isBlank() && !c.getPaternalSurname().isBlank()) {
            c.setFullName((c.getFirstNames() + " " + c.getPaternalSurname() + (c.getMaternalSurname() != null && !c.getMaternalSurname().isBlank() ? " " + c.getMaternalSurname() : "")).trim());
        }
        if (r.getPhoneNumber() != null) c.setPhoneNumber(r.getPhoneNumber().trim());
        if (r.getEmail() != null) c.setEmail(r.getEmail().trim().toLowerCase(Locale.ROOT));
        if (r.getBirthDate() != null) c.setBirthDate(r.getBirthDate());
        if (r.getSex() != null && !r.getSex().isBlank()) c.setSex(r.getSex().trim().substring(0, 1).toUpperCase(Locale.ROOT));
        if (r.getCurp() != null) c.setCurp(r.getCurp().trim().toUpperCase(Locale.ROOT));
        if (r.getRfc() != null) c.setRfc(r.getRfc().trim().toUpperCase(Locale.ROOT));
        if (r.getNationality() != null) c.setNationality(r.getNationality().trim());
        if (r.getOccupation() != null) c.setOccupation(r.getOccupation().trim());
        if (r.getAddressLine() != null) c.setAddressLine(r.getAddressLine().trim());
        if (r.getPostalCode() != null) c.setPostalCode(r.getPostalCode().trim());
        if (r.getState() != null) c.setState(r.getState().trim());
        if (r.getPep() != null) c.setPep(r.getPep());
    }

    private CustomerResponse view(Customer c, KYC k) {
        Card card = cardRepository.findByCustomer(c).stream().findFirst().orElse(null);
        BigDecimal balance = BigDecimal.ZERO;
        if (card != null) {
            LedgerAccount account = ledgerAccountRepository.findByCard(card).orElse(null);
            if (account != null) balance = ledgerEntryRepository.calculateBalance(account);
        }
        List<KycService.Check> checks = kyc.checks(k);
        return CustomerResponse.builder()
                .id(c.getId())
                .fullName(c.getFullName())
                .phoneNumber(c.getPhoneNumber())
                .email(c.getEmail())
                .curpMasked(KycService.mask(c.getCurp()))
                .rfcMasked(KycService.mask(c.getRfc()))
                .birthDate(c.getBirthDate() != null ? c.getBirthDate().toString() : null)
                .state(c.getState())
                .pep(c.isPep())
                .kycStatus(k != null && k.getStatus() != null ? k.getStatus().name() : "PENDING")
                .kycRiskLevel(k != null ? k.getRiskLevel() : null)
                .kycFailed(checks.stream().filter(ch -> !ch.ok()).map(KycService.Check::code).toList())
                .kyc(kyc.view(k))
                .cardId(card != null ? card.getId() : null)
                .cardLast4(card != null ? card.getLast4() : null)
                .cardStatus(card != null ? card.getStatus().name() : null)
                .balance(balance)
                .build();
    }
}
