package ke.co.technopick.mfi.core.eod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Action;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scheduled end of day (increment 29, ADR-0029) through the real application and PostgreSQL 17,
 * with the application clock set by the test so every schedule path is exercised deterministically.
 */
@Testcontainers
@SpringBootTest
class EndOfDaySchedulerIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000d4";
    static final String OTHER = "00000000-0000-0000-0000-0000000000e5";
    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "mfi_app");
        r.add("spring.datasource.password", () -> "it-only-not-a-secret");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
        r.add("mfi.jobs.enabled", () -> "false");
    }

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-28T12:00:00Z");

        void set(String nairobiLocal) {
            now = LocalDateTime.parse(nairobiLocal).atZone(NAIROBI).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("test decoder accepts no tokens");
            };
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestClock clock;
    @Autowired
    EndOfDayScheduler scheduler;

    MockMvc mvc;

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor as(String tenant, String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", tenant)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static JwtRequestPostProcessor ops() {
        return as(T, "ops-1", "OPERATIONS");
    }

    static JwtRequestPostProcessor supervisor() {
        return as(T, "supervisor-1", "SUPERVISOR");
    }

    String businessDate() {
        return owner().queryForObject("SELECT current_date_::text FROM tenancy.business_date WHERE tenant_id = ?::uuid",
                String.class, T);
    }

    Action tick(String nairobiLocal) {
        clock.set(nairobiLocal);
        return scheduler.tickTenant(UUID.fromString(T), clock.instant());
    }

    void expectAlerts(String... codes) throws Exception {
        var r = mvc.perform(get("/api/v1/end-of-day/alerts").with(ops())).andExpect(status().isOk()).andReturn();
        List<String> found = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$[*].code");
        assertEquals(List.of(codes), found, r.getResponse().getContentAsString());
    }

    void expectLatestAttempt(String trigger, String outcome, String actor) throws Exception {
        mvc.perform(get("/api/v1/end-of-day/attempts?limit=1").with(ops())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].trigger").value(trigger))
            .andExpect(jsonPath("$[0].outcome").value(outcome))
            .andExpect(jsonPath("$[0].actor").value(actor));
    }

    int attemptCount() {
        return owner().queryForObject("SELECT count(*) FROM ledger.eod_attempt WHERE tenant_id = ?::uuid", Integer.class, T);
    }

    static String schedule(String enabled, String runAt, int overrun, int attempts, int retry, int catchUp) {
        return """
            {"enabled":%s,"runAt":"%s","overrunMinutes":%d,"maxAttempts":%d,"retryMinutes":%d,"maxCatchUpDays":%d}
            """.formatted(enabled, runAt, overrun, attempts, retry, catchUp);
    }

    @Test
    void scheduledEndOfDayRetriesRecoversAndAlerts() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        owner().execute("""
            INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency, time_zone)
            VALUES ('%1$s', 'SCHED-TEST', 'Schedule test SACCO (TEST-ONLY)', 'DEPOSIT_TAKING_SACCO', true, 'KES', 'Africa/Nairobi'),
                   ('%2$s', 'OTHER-TEST', 'Other tenant (TEST-ONLY)', 'DEPOSIT_TAKING_SACCO', true, 'KES', 'Africa/Nairobi');
            INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES
              ('%1$s', '2026-11', '2026-11-01', '2026-11-30');
            INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES ('%1$s', '2026-11-28');
            """.formatted(T, OTHER));

        // The schedule is off until a supervisor switches it on; only supervisors may change it.
        clock.set("2026-11-28T12:00");
        mvc.perform(get("/api/v1/end-of-day/schedule").with(ops())).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.runAt").value("21:00"))
            .andExpect(jsonPath("$.timeZone").value("Africa/Nairobi"));
        assertEquals(Action.NOT_ENABLED, tick("2026-11-29T08:00"));
        mvc.perform(put("/api/v1/end-of-day/schedule").with(ops()).contentType(MediaType.APPLICATION_JSON)
                .content(schedule("true", "21:00", 60, 3, 15, 2))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/end-of-day/schedule").with(supervisor()).contentType(MediaType.APPLICATION_JSON)
                .content(schedule("true", "9pm", 60, 3, 15, 2))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/end-of-day/schedule").with(supervisor()).contentType(MediaType.APPLICATION_JSON)
                .content(schedule("true", "21:00", 2, 3, 15, 2))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/end-of-day/schedule").with(supervisor()).contentType(MediaType.APPLICATION_JSON)
                .content(schedule("true", "21:00", 60, 3, 15, 2))).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.updatedBy").value("user:supervisor-1"))
            .andExpect(jsonPath("$.nextCutOff").value("2026-11-28T21:00:00+03:00"));
        assertEquals(0, attemptCount());

        // 1. On time: nothing before 21:00 Nairobi, the day closes at 21:00.
        assertEquals(Action.NOT_DUE, tick("2026-11-28T20:59"));
        assertEquals(0, attemptCount());
        assertEquals(Action.RUN, tick("2026-11-28T21:00"));
        assertEquals("2026-11-29", businessDate());
        expectLatestAttempt("SCHEDULED", "COMPLETED", EndOfDayScheduler.SCHEDULER);

        // 2. The platform was down for two nights: the missed days close one at a time, oldest first,
        //    until one is blocked (December has no open period yet). A blocked day is retried after
        //    the interval, and closes once finance opens the period.
        assertEquals(Action.RUN, tick("2026-12-01T09:00"));
        assertEquals("2026-11-30", businessDate());
        assertEquals(Action.RUN, tick("2026-12-01T09:01"));
        assertEquals("2026-11-30", businessDate());
        expectLatestAttempt("SCHEDULED", "BLOCKED", EndOfDayScheduler.SCHEDULER);
        mvc.perform(get("/api/v1/end-of-day/attempts?limit=1").with(ops()))
            .andExpect(jsonPath("$[0].reason").value(org.hamcrest.Matchers.containsString("no open accounting period")));
        expectAlerts("EOD_BLOCKED", "EOD_LATE");
        assertEquals(Action.WAIT_RETRY, tick("2026-12-01T09:10"));
        owner().update("INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES (?::uuid, '2026-12', '2026-12-01', '2026-12-31')", T);
        assertEquals(Action.RUN, tick("2026-12-01T09:16"));
        assertEquals("2026-12-01", businessDate());
        expectAlerts();

        // 3. A failure is retried every 15 minutes, three times, then left for a person (critical alert).
        owner().execute("""
            CREATE FUNCTION ledger.sched_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'SCHED_TEST_INJECTED: reconciliation store unavailable'; END $$;
            CREATE TRIGGER sched_test_fail BEFORE INSERT ON ledger.reconciliation_result
              FOR EACH ROW EXECUTE FUNCTION ledger.sched_test_fail();
            """);
        assertEquals(Action.RUN, tick("2026-12-01T21:00"));
        expectLatestAttempt("SCHEDULED", "FAILED", EndOfDayScheduler.SCHEDULER);
        mvc.perform(get("/api/v1/end-of-day/alerts").with(ops()))
            .andExpect(jsonPath("$[0].code").value("EOD_FAILED")).andExpect(jsonPath("$[0].severity").value("WARNING"));
        assertEquals(Action.WAIT_RETRY, tick("2026-12-01T21:10"));
        assertEquals(Action.RUN, tick("2026-12-01T21:15"));
        assertEquals(Action.RUN, tick("2026-12-01T21:30"));
        assertEquals(Action.GIVE_UP, tick("2026-12-01T21:45"));
        assertEquals(Action.GIVE_UP, tick("2026-12-01T23:00"));
        assertEquals(3, owner().queryForObject("""
                SELECT count(*) FROM ledger.eod_attempt WHERE tenant_id = ?::uuid AND business_date = '2026-12-01' AND outcome = 'FAILED'
                """, Integer.class, T));
        mvc.perform(get("/api/v1/end-of-day/alerts").with(ops()))
            .andExpect(jsonPath("$[0].code").value("EOD_FAILED")).andExpect(jsonPath("$[0].severity").value("CRITICAL"))
            .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("Automatic retries stopped after 3 attempts")));
        mvc.perform(get("/api/v1/end-of-day").with(ops())).andExpect(jsonPath("$.runStatus").value("FAILED"))
            .andExpect(jsonPath("$.runInProgress").value(false));
        assertEquals("2026-12-01", businessDate());

        // The cause is fixed; operations runs end of day and it resumes and completes.
        owner().execute("DROP TRIGGER sched_test_fail ON ledger.reconciliation_result; DROP FUNCTION ledger.sched_test_fail()");
        mvc.perform(post("/api/v1/end-of-day/run").with(ops())).andExpect(status().isOk())
            .andExpect(jsonPath("$.closedDate").value("2026-12-01"));
        expectLatestAttempt("MANUAL", "COMPLETED", "user:ops-1");
        expectAlerts();

        // 4. A process died mid-run (run RUNNING, nobody holds the lock): reported as interrupted and
        //    resumed at once, not after a timeout; the dead attempt is marked INTERRUPTED.
        clock.set("2026-12-02T21:00");
        owner().update("INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (?::uuid, '2026-12-02')", T);
        owner().update("""
            INSERT INTO ledger.eod_attempt (tenant_id, business_date, trigger, actor, started_at)
            VALUES (?::uuid, '2026-12-02', 'SCHEDULED', 'system:eod-scheduler', '2026-12-02 21:00+03')
            """, T);
        mvc.perform(get("/api/v1/end-of-day").with(ops())).andExpect(jsonPath("$.runStatus").value("RUNNING"))
            .andExpect(jsonPath("$.runInProgress").value(false));
        clock.set("2026-12-02T21:02");
        expectAlerts("EOD_INTERRUPTED");
        mvc.perform(post("/api/v1/end-of-day/run").with(ops())).andExpect(status().isOk())
            .andExpect(jsonPath("$.closedDate").value("2026-12-02"));
        assertEquals("INTERRUPTED", owner().queryForObject("""
                SELECT outcome FROM ledger.eod_attempt WHERE tenant_id = ?::uuid AND business_date = '2026-12-02'
                 ORDER BY attempt_id LIMIT 1
                """, String.class, T));

        // 5. A live run that takes longer than expected is never resumed beside itself: it is reported
        //    as overrunning, the scheduler leaves it alone and a person's run is refused (SKIPPED).
        try (Connection node = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            try (PreparedStatement st = node.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                st.setString(1, "mfi:eod:" + T);
                st.executeQuery().close();
            }
            owner().update("INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (?::uuid, '2026-12-03')", T);
            owner().update("""
                INSERT INTO ledger.eod_attempt (tenant_id, business_date, trigger, actor, started_at)
                VALUES (?::uuid, '2026-12-03', 'SCHEDULED', 'system:eod-scheduler', '2026-12-03 21:00+03')
                """, T);
            clock.set("2026-12-03T22:30");
            mvc.perform(get("/api/v1/end-of-day").with(ops())).andExpect(jsonPath("$.runInProgress").value(true));
            expectAlerts("EOD_OVERRUN");
            assertEquals(Action.IN_PROGRESS, tick("2026-12-03T22:31"));
            mvc.perform(post("/api/v1/end-of-day/run").with(ops())).andExpect(status().isConflict());
            expectLatestAttempt("MANUAL", "SKIPPED", "user:ops-1");
        }
        // That process ends (its connection closes): the run is now interrupted (and the day is late), and resumes.
        expectAlerts("EOD_INTERRUPTED", "EOD_LATE");
        assertEquals(Action.RUN, tick("2026-12-03T22:47"));
        assertEquals("2026-12-04", businessDate());
        assertEquals(0, owner().queryForObject(
                "SELECT count(*) FROM ledger.eod_attempt WHERE tenant_id = ?::uuid AND outcome = 'RUNNING'", Integer.class, T));

        // 6. Too far behind for automatic catch-up (limit 2 days): nothing runs; a critical alert asks for a person.
        int before = attemptCount();
        assertEquals(Action.TOO_FAR_BEHIND, tick("2026-12-10T12:00"));
        assertEquals(before, attemptCount());
        mvc.perform(get("/api/v1/end-of-day/alerts").with(ops()))
            .andExpect(jsonPath("$[0].code").value("EOD_BEHIND")).andExpect(jsonPath("$[0].severity").value("CRITICAL"));

        // A whole scheduler pass copes with a tenant that is not set up (no business date) and moves on.
        scheduler.tick(clock.instant());

        // Other tenants see none of this; officers cannot read it.
        mvc.perform(get("/api/v1/end-of-day/attempts").with(as(OTHER, "ops-2", "OPERATIONS")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/end-of-day/alerts").with(as(T, "officer-1", "LOAN_OFFICER"))).andExpect(status().isForbidden());
        assertTrue(attemptCount() >= 12);
    }
}
