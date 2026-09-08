package bank.cardissuing.fraud.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.fraud.domain.AuthorizationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface AuthorizationAttemptRepository extends JpaRepository<AuthorizationAttempt, Long> {

    long countByCardAndCreatedAtAfter(Card card, LocalDateTime since);

    long countByCardAndApprovedFalseAndCreatedAtAfter(Card card, LocalDateTime since);

    List<AuthorizationAttempt> findByCardAndApprovedTrueAndCreatedAtAfter(Card card, LocalDateTime since);

    Optional<AuthorizationAttempt> findFirstByCardAndApprovedTrueOrderByCreatedAtDesc(Card card);

    List<AuthorizationAttempt> findFirst100ByCardOrderByCreatedAtDesc(Card card);

    long countByMerchantIdAndApprovedFalseAndCreatedAtAfter(String merchantId, LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT a.card.id) FROM AuthorizationAttempt a " +
           "WHERE a.merchantId = :merchantId AND a.approved = false AND a.createdAt >= :since")
    long countDistinctCardsDeclinedAtMerchant(@Param("merchantId") String merchantId, @Param("since") LocalDateTime since);

    Page<AuthorizationAttempt> findByCardOrderByCreatedAtDesc(Card card, Pageable pageable);

    Page<AuthorizationAttempt> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
