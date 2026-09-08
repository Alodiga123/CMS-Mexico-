package bank.cardissuing.reports.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One generated report, kept as evidence: what was asked, who asked, the CSV
 * exactly as it was delivered and its SHA-256 seal. Regulators and auditors get
 * the file plus the seal; anyone can later verify the stored copy is untouched.
 */
@Entity
@Table(name = "report_runs", indexes = {
        @Index(name = "ix_report_runs_code", columnList = "report_code"),
        @Index(name = "ix_report_runs_created", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ReportRun extends BaseEntity {

    @Column(name = "report_code", nullable = false, length = 40)
    private String reportCode;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    /** The extra parameters that shaped the run, as key=value pairs. */
    @Column(length = 400)
    private String params;

    @Column(name = "generated_by", length = 80)
    private String generatedBy;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 10)
    private String format = "CSV";

    @Column(columnDefinition = "text")
    private String csv;
}
