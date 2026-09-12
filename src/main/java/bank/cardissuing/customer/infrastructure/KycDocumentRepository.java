package bank.cardissuing.customer.infrastructure;

import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycDocumentRepository extends JpaRepository<KycDocument, Long> {
    List<KycDocument> findByCustomerOrderByUploadedAtDesc(Customer customer);
    Optional<KycDocument> findFirstByCustomerAndSideOrderByUploadedAtDesc(Customer customer, KycDocument.Side side);
}
