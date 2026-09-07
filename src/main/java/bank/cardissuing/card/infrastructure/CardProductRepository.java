package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardProductRepository extends JpaRepository<CardProduct, Long> {
    Optional<CardProduct> findByProductCode(String productCode);
}
