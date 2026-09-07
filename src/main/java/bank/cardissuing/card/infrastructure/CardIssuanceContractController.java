package bank.cardissuing.card.infrastructure;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.CardIssuanceContract;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registro de empresas/personas que solicitan lanzar un programa de
 * tarjetas, formalizado mediante un contrato de emisión. Una tarjeta
 * (CardProduct) solo puede publicarse cuando está ligada a un contrato
 * en estado ACTIVE.
 */
@Slf4j
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class CardIssuanceContractController {

    private final CardIssuanceContractRepository contractRepository;
    private final CustomerRepository customerRepository;
    private final CardProductRepository cardProductRepository;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<ContractResponse>> getAllContracts() {
        List<ContractResponse> responses = contractRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ContractResponse> createContract(@RequestBody ContractCreateRequest request) {
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", request.getCustomerId()));

        CardIssuanceContract contract = new CardIssuanceContract();
        contract.setCustomer(customer);
        contract.setContractNumber(request.getContractNumber() != null && !request.getContractNumber().isBlank()
                ? request.getContractNumber()
                : "CTR-" + System.currentTimeMillis() % 1000000);
        contract.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
        contract.setEndDate(request.getEndDate());
        contract.setTerms(request.getTerms());
        contract = contractRepository.save(contract);

        auditService.log("CREATE_CONTRACT", "CardIssuanceContract", contract.getId().toString(), "SYSTEM");
        log.info("Contract created: id={}, customerId={}", contract.getId(), customer.getId());

        return ResponseEntity.ok(toResponse(contract));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<ContractResponse> updateContract(@PathVariable Long id, @RequestBody ContractCreateRequest request) {
        CardIssuanceContract contract = getContract(id);

        if (request.getContractNumber() != null && !request.getContractNumber().isBlank()) {
            contract.setContractNumber(request.getContractNumber());
        }
        if (request.getStartDate() != null) contract.setStartDate(request.getStartDate());
        contract.setEndDate(request.getEndDate());
        if (request.getTerms() != null) contract.setTerms(request.getTerms());

        contract = contractRepository.save(contract);
        auditService.log("UPDATE_CONTRACT", "CardIssuanceContract", contract.getId().toString(), "SYSTEM");
        return ResponseEntity.ok(toResponse(contract));
    }

    @PatchMapping("/{id}/activate")
    @Transactional
    public ResponseEntity<ContractResponse> activateContract(@PathVariable Long id) {
        CardIssuanceContract contract = getContract(id);
        contract.activate();
        contract = contractRepository.save(contract);
        auditService.log("ACTIVATE_CONTRACT", "CardIssuanceContract", contract.getId().toString(), "SYSTEM");
        return ResponseEntity.ok(toResponse(contract));
    }

    @PatchMapping("/{id}/suspend")
    @Transactional
    public ResponseEntity<ContractResponse> suspendContract(@PathVariable Long id) {
        CardIssuanceContract contract = getContract(id);
        contract.suspend();
        contract = contractRepository.save(contract);
        auditService.log("SUSPEND_CONTRACT", "CardIssuanceContract", contract.getId().toString(), "SYSTEM");
        return ResponseEntity.ok(toResponse(contract));
    }

    @PatchMapping("/{id}/terminate")
    @Transactional
    public ResponseEntity<ContractResponse> terminateContract(@PathVariable Long id) {
        CardIssuanceContract contract = getContract(id);
        contract.terminate();
        contract = contractRepository.save(contract);
        auditService.log("TERMINATE_CONTRACT", "CardIssuanceContract", contract.getId().toString(), "SYSTEM");
        return ResponseEntity.ok(toResponse(contract));
    }

    private CardIssuanceContract getContract(Long id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CardIssuanceContract", "id", id));
    }

    private ContractResponse toResponse(CardIssuanceContract contract) {
        long productCount = cardProductRepository.findAll().stream()
                .filter(p -> p.getContract() != null && p.getContract().getId().equals(contract.getId()))
                .count();

        return new ContractResponse(
                contract.getId(),
                contract.getContractNumber(),
                contract.getCustomer() != null ? contract.getCustomer().getId() : null,
                contract.getCustomer() != null ? contract.getCustomer().getDisplayName() : null,
                contract.getCustomer() != null ? contract.getCustomer().getCustomerType().name() : null,
                contract.getStatus().name(),
                contract.getStartDate(),
                contract.getEndDate(),
                contract.getSignedAt(),
                contract.getTerms(),
                productCount
        );
    }

    @Data
    public static class ContractCreateRequest {
        private Long customerId;
        private String contractNumber;
        private LocalDate startDate;
        private LocalDate endDate;
        private String terms;
    }

    @Data
    public static class ContractResponse {
        private final Long id;
        private final String contractNumber;
        private final Long customerId;
        private final String customerName;
        private final String customerType;
        private final String status;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final java.time.LocalDateTime signedAt;
        private final String terms;
        private final long productCount;
    }
}
