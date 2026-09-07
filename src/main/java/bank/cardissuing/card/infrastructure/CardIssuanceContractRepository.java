package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.CardIssuanceContract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardIssuanceContractRepository extends JpaRepository<CardIssuanceContract, Long> {
}
