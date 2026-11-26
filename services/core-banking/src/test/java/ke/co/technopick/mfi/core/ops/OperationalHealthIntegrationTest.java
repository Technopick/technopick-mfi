package ke.co.technopick.mfi.core.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ke.co.technopick.mfi.core.ops.application.OperationalHealth;
import ke.co.technopick.mfi.core.ops.application.OperationalHealth.ChainStatus;
import ke.co.technopick.mfi.core.ops.application.OperationalHealth.Figure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import org.testcontainers.utility.MountableFile;

/**
 * Monitoring (increment 45, ADR-0045) on the small TEST-ONLY book: the operational figures are read
 * per tenant, published at /actuator/prometheus only to the monitoring service account, follow the
 * data (an event stuck in the outbox, a changed audit row), and give the monitoring account nothing
 * on the business API.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class OperationalHealthIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";

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

    @TestConfiguration
    static class Config {
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
    OperationalHealth health;

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static final JwtRequestPostProcessor MONITORING = jwt().jwt(j -> j.subject("prometheus"))
        .authorities(new SimpleGrantedAuthority("ROLE_MONITORING"));
    static final JwtRequestPostProcessor FINANCE = jwt().jwt(j -> j.subject("fin-1").claim("tenant_id", T))
        .authorities(new SimpleGrantedAuthority("ROLE_FINANCE"));

    static BigDecimal figure(List<Figure> figures, String metric) {
        return figures.stream().filter(f -> "TEST-PERF".equals(f.tenant()) && metric.equals(f.metric())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + metric)).value();
    }

    static double scraped(String body, String metric) {
        Matcher m = Pattern.compile("(?m)^" + metric + "\\{tenant=\"TEST-PERF\"\\} ([0-9.eE+-]+)$").matcher(body);
        assertTrue(m.find(), metric + " not in the scrape");
        return Double.parseDouble(m.group(1));
    }

    @Test
    void figuresArePublishedToMonitoringOnly() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String sql = new String(OperationalHealthIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("ops-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());

        // Figures for the tenant; nothing stuck yet.
        List<Figure> figures = health.refresh();
        assertNotNull(figure(figures, "eod_days_behind"));
        assertEquals(0, figure(figures, "reconciliation_differences").signum());
        assertEquals(0, figure(figures, "b2c_outcome_unknown").signum());
        assertEquals(0, figure(figures, "eod_last_attempt_failed").signum());

        // TEST-ONLY: an event left unpublished for an hour.
        owner().update("""
                INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload, occurred_at)
                VALUES ('0e000000-0000-0000-0000-0000000000c3', ?::uuid, 'OpsTestEvent', 'test', 'it', '{}'::jsonb,
                        now() - interval '1 hour')
                """, T);
        figures = health.refresh();
        assertTrue(figure(figures, "outbox_oldest_unpublished_seconds").compareTo(new BigDecimal("3600")) >= 0);

        // Audit chain: three links, intact; then a row changed behind the application's back.
        owner().execute("""
                SELECT set_config('app.tenant_id', '%1$s', false);
                INSERT INTO audit.staff_action (tenant_id, chain_no, occurred_at, actor, roles, method, route, path, status, duration_ms,
                                                request_id, prev_hash, hash)
                SELECT '%1$s', 0, now(), 'user:teller-' || g, 'TELLER', 'POST', '/api/v1/teller/deposits', '/api/v1/teller/deposits',
                       200, 10, 'it-' || g, '', '' FROM generate_series(1, 3) g;
                """.formatted(T));
        List<ChainStatus> chains = health.refreshAuditChains();
        assertEquals(1, chains.size());
        assertNull(chains.get(0).brokenAt());
        assertEquals(3, chains.get(0).links());

        // The scrape: monitoring only.
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(FINANCE)).andExpect(status().isForbidden());
        String body = mvc.perform(get("/actuator/prometheus").with(MONITORING)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(scraped(body, "mfi_outbox_oldest_unpublished_seconds") >= 3600);
        assertEquals(0.0, scraped(body, "mfi_reconciliation_differences"));
        assertEquals(0.0, scraped(body, "mfi_audit_chain_broken"));
        assertEquals(3.0, scraped(body, "mfi_audit_chain_links"));
        assertTrue(body.contains("mfi_ops_snapshot_last_success_seconds"), "snapshot timestamp published");
        assertTrue(body.contains("hikaricp_connections"), "connection pool metrics published");

        // The monitoring account gets nothing from the business API.
        mvc.perform(get("/api/v1/lending/guarantor-calls").with(MONITORING)).andExpect(status().isForbidden());

        // TEST-ONLY tampering (superuser, triggers bypassed): the chain check reports it.
        owner().execute("""
                SET session_replication_role = replica;
                UPDATE audit.staff_action SET actor = 'user:someone-else' WHERE tenant_id = '%s' AND chain_no = 2;
                """.formatted(T));
        chains = health.refreshAuditChains();
        assertEquals(2L, chains.get(0).brokenAt());
        body = mvc.perform(get("/actuator/prometheus").with(MONITORING)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(1.0, scraped(body, "mfi_audit_chain_broken"));
    }
}
