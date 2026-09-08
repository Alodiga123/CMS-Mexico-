package bank.cardissuing.funds.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuthorizationHoldRepository extends JpaRepository<AuthorizationHold, Long> {

    Optional<AuthorizationHold> findByApprovalCode(String approvalCode);

    Optional<AuthorizationHold> findByIdempotencyKey(String idempotencyKey);

    /** Everything reserved and not yet settled or released: the shadow balance. */
    @Query("SELECT COALESCE(SUM(h.amount), 0) FROM AuthorizationHold h " +
           "WHERE h.card = :card AND h.status = :status")
    BigDecimal sumByCardAndStatus(@Param("card") Card card, @Param("status") HoldStatus status);

    /** Approved volume inside a time window, for daily / weekly / monthly limits. */
    @Query("SELECT COALESCE(SUM(h.amount), 0) FROM AuthorizationHold h " +
           "WHERE h.card = :card AND h.status IN (:statuses) AND h.createdAt >= :since")
    BigDecimal sumSince(@Param("card") Card card,
                        @Param("statuses") Collection<HoldStatus> statuses,
                        @Param("since") LocalDateTime since);

    List<AuthorizationHold> findByStatusAndExpiresAtBefore(HoldStatus status, LocalDateTime before);

    List<AuthorizationHold> findByCardOrderByCreatedAtDesc(Card card);

    Optional<AuthorizationHold> findFirstByRrnOrderByCreatedAtDesc(String rrn);

    Optional<AuthorizationHold> findFirstByStanAndAcquirerIdAndCreatedAtAfterOrderByCreatedAtDesc(String stan, String acquirerId, LocalDateTime after);

    List<AuthorizationHold> findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc();

    @Query("SELECT COALESCE(SUM(h.amount), 0) FROM AuthorizationHold h WHERE h.card = :card AND h.standIn = true AND h.createdAt >= :since")
    BigDecimal standInAmountSince(@Param("card") Card card, @Param("since") LocalDateTime since);

    long countByCardAndStandInTrueAndCreatedAtAfter(Card card, LocalDateTime since);
}
