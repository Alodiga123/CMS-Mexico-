package bank.cardissuing.reports.infrastructure;

import bank.cardissuing.reports.domain.ReportRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRunRepository extends JpaRepository<ReportRun, Long> {
    List<ReportRun> findTop100ByOrderByCreatedAtDesc();
    List<ReportRun> findTop100ByReportCodeOrderByCreatedAtDesc(String reportCode);
}
