package bank.cardissuing.plastics.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.plastics.domain.Plastic;
import bank.cardissuing.plastics.domain.PlasticBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlasticRepository extends JpaRepository<Plastic, Long> {

    List<Plastic> findByCardOrderBySequenceDesc(Card card);

    List<Plastic> findByStatusOrderByCreatedAtAsc(Plastic.Status status);

    List<Plastic> findByStatusInOrderByCreatedAtDesc(Collection<Plastic.Status> statuses);

    List<Plastic> findByBatchOrderBySequenceAsc(PlasticBatch batch);

    List<Plastic> findAllByOrderByCreatedAtDesc();

    boolean existsByCardAndStatusNotIn(Card card, Collection<Plastic.Status> closed);

    int countByCard(Card card);
}
