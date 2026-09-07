package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    List<Promotion> findByTargetProduct(CardProduct targetProduct);
    List<Promotion> findByActiveTrue();
}
