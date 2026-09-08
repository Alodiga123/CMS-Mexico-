package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.customer.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, Long> {
    java.util.List<Card> findByCustomer(Customer customer);

    java.util.List<Card> findByExternalAccountId(String externalAccountId);

    java.util.List<Card> findByExternalAccountIdIsNotNull();
    java.util.List<Card> findByStatusAndExpiryDateBefore(bank.cardissuing.card.domain.CardStatus status, java.time.LocalDate before);
}
