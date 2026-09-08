package bank.cardissuing.fraud.infrastructure;

import bank.cardissuing.fraud.domain.BlockedEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BlockedEntityRepository extends JpaRepository<BlockedEntity, Long> {

    Optional<BlockedEntity> findByTypeAndValue(BlockedEntity.Type type, String value);

    Optional<BlockedEntity> findByTypeAndValueAndActiveTrue(BlockedEntity.Type type, String value);

    List<BlockedEntity> findByActiveTrueOrderByCreatedAtDesc();
}
