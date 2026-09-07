package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardControls;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardControlsRepository extends JpaRepository<CardControls, Long> {
    Optional<CardControls> findByCard(Card card);
}
