package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, Long> {
    java.util.List<Card> findByCustomer(Customer customer);

    java.util.List<Card> findByExternalAccountId(String externalAccountId);

    java.util.List<Card> findByExternalAccountIdIsNotNull();
    java.util.List<Card> findByStatusAndExpiryDateBefore(bank.cardissuing.card.domain.CardStatus status, java.time.LocalDate before);
    java.util.List<Card> findByLast4(String last4);

    @org.springframework.data.jpa.repository.Query("SELECT c FROM Card c WHERE (:status IS NULL OR c.status = :status) AND (:last4 IS NULL OR c.last4 = :last4) AND (:productId IS NULL OR c.product.id = :productId) ORDER BY c.id DESC")
    org.springframework.data.domain.Page<Card> search(@org.springframework.data.repository.query.Param("status") bank.cardissuing.card.domain.CardStatus status,
                                                      @org.springframework.data.repository.query.Param("last4") String last4,
                                                      @org.springframework.data.repository.query.Param("productId") Long productId,
                                                      org.springframework.data.domain.Pageable pageable);
    java.util.Optional<Card> findByPanHash(String panHash);
}
