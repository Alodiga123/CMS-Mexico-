package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.fraud.guild.domain.GuildAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GuildAlertRepository extends JpaRepository<GuildAlert, Long> {
    List<GuildAlert> findByStatusInOrderByCreatedAtAsc(Collection<GuildAlert.Status> statuses);
    List<GuildAlert> findTop200ByOrderByCreatedAtDesc();
    List<GuildAlert> findTop200ByDirectionOrderByCreatedAtDesc(GuildAlert.Direction direction);
    List<GuildAlert> findTop200ByStatusOrderByCreatedAtDesc(GuildAlert.Status status);
    List<GuildAlert> findTop200ByDirectionAndStatusOrderByCreatedAtDesc(GuildAlert.Direction direction, GuildAlert.Status status);
    Optional<GuildAlert> findByGuildFolio(String folio);
    boolean existsByDirectionAndTypeAndMerchantIdAndStatusInAndCreatedAtAfter(GuildAlert.Direction direction, GuildAlert.Type type, String merchantId,
                                                                            Collection<GuildAlert.Status> statuses, LocalDateTime after);
    List<GuildAlert> findByStatusInAndRespondByBefore(Collection<GuildAlert.Status> statuses, LocalDate before);
    long countByStatus(GuildAlert.Status status);
    long countByStatusInAndRespondByBefore(Collection<GuildAlert.Status> statuses, LocalDate before);
    Optional<GuildAlert> findTopByDirectionOrderByReceivedAtDesc(GuildAlert.Direction direction);
}
