package bank.cardissuing.clearing.infrastructure;

import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.clearing.domain.SettlementCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementCycleRepository extends JpaRepository<SettlementCycle, Long> {
    Optional<SettlementCycle> findByNetworkAndCycleDate(Network network, LocalDate cycleDate);
    List<SettlementCycle> findTop100ByOrderByCycleDateDescNetworkAsc();
    List<SettlementCycle> findByStatusOrderByCycleDateDesc(SettlementCycle.Status status);
}
