package bank.cardissuing.common.bootstrap;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The schema is kept by Hibernate ({@code ddl-auto=update}), which adds columns but never touches
 * an existing CHECK constraint. When an enum grows a value, the constraint Hibernate wrote for the
 * old values would reject the new one; these idempotent statements bring such constraints up to
 * date at start-up. Each one is safe to run on every boot and on an empty database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaFixups {

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void apply() {
        // KYCStatus gained REVIEW (analyst queue)
        run("ALTER TABLE kyc DROP CONSTRAINT IF EXISTS kyc_status_check");
        run("ALTER TABLE kyc ADD CONSTRAINT kyc_status_check CHECK (status IN ('PENDING','VERIFIED','REVIEW','REJECTED'))");
    }

    private void run(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.warn("Schema fixup skipped ({}): {}", sql, e.getMessage());
        }
    }
}
