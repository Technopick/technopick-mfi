package ke.co.technopick.mfi.core.tenancy.persistence;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads the tenant's current business date (tenant already bound by {@link TenantSession}). */
@Component
public class BusinessDates {

    private final JdbcClient jdbc;

    public BusinessDates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate current() {
        return jdbc.sql("SELECT current_date_ FROM tenancy.business_date")
            .query(LocalDate.class)
            .optional()
            .orElseThrow(() -> new IllegalStateException("tenant has no business date configured"));
    }
}
