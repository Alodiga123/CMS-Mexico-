package bank.cardissuing.customer.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KYC;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.infrastructure.dto.CustomerCreateRequest;
import bank.cardissuing.customer.infrastructure.dto.CustomerResponse;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final KYCRepository kycRepository;
    private final CardRepository cardRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping
    @Transactional
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerCreateRequest request) {
        log.info("Creating new customer: {}", request.getFullName());

        // 1. Save Customer
        Customer customer = new Customer();
        customer.setFullName(request.getFullName());
        customer.setPhoneNumber(request.getPhoneNumber());
        customer = customerRepository.save(customer);

        // 2. Save KYC (Verified)
        KYC kyc = new KYC();
        kyc.setCustomer(customer);
        kyc.setStatus(KYCStatus.VERIFIED);
        kyc.setDocumentType("PASSPORT");
        kyc.setDocumentNumber("DOC-" + System.currentTimeMillis() % 100000);
        kyc.setVerifiedAt(LocalDateTime.now());
        kycRepository.save(kyc);

        // 3. Issue Card
        String last4 = (request.getCardLast4() != null && request.getCardLast4().length() == 4) 
                ? request.getCardLast4() 
                : String.format("%04d", new Random().nextInt(10000));

        Card card = new Card();
        card.setCustomer(customer);
        card.setLast4(last4);
        card.setStatus(CardStatus.ACTIVE);
        card.setExpiryDate(LocalDate.now().plusYears(3));
        card = cardRepository.save(card);

        // 4. Create Ledger Account & Initial Balance
        LedgerAccount account = new LedgerAccount();
        account.setCard(card);
        account.setCurrency("VND");
        account = ledgerAccountRepository.save(account);

        BigDecimal initialDeposit = request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO;
        if (initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            LedgerEntry entry = new LedgerEntry(account, EntryType.CREDIT, initialDeposit, "Initial Deposit");
            ledgerEntryRepository.save(entry);
        }

        BigDecimal balance = ledgerEntryRepository.calculateBalance(account);

        CustomerResponse response = CustomerResponse.builder()
                .id(customer.getId())
                .fullName(customer.getFullName())
                .phoneNumber(customer.getPhoneNumber())
                .kycStatus(kyc.getStatus().name())
                .cardId(card.getId())
                .cardLast4(card.getLast4())
                .cardStatus(card.getStatus().name())
                .balance(balance)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> getAllCustomers() {
        List<Customer> customers = customerRepository.findAll();
        List<CustomerResponse> responses = customers.stream().map(customer -> {
            KYC kyc = kycRepository.findByCustomer(customer).orElse(null);
            Card card = cardRepository.findByCustomer(customer).stream().findFirst().orElse(null);
            BigDecimal balance = BigDecimal.ZERO;
            if (card != null) {
                LedgerAccount account = ledgerAccountRepository.findByCard(card).orElse(null);
                if (account != null) {
                    balance = ledgerEntryRepository.calculateBalance(account);
                }
            }
            return CustomerResponse.builder()
                    .id(customer.getId())
                    .fullName(customer.getFullName())
                    .phoneNumber(customer.getPhoneNumber())
                    .kycStatus(kyc != null ? kyc.getStatus().name() : "N/A")
                    .cardId(card != null ? card.getId() : null)
                    .cardLast4(card != null ? card.getLast4() : "N/A")
                    .cardStatus(card != null ? card.getStatus().name() : "N/A")
                    .balance(balance)
                    .build();
        }).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }
}
