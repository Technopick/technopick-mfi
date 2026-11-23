package ke.co.technopick.mfi.core.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
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
 * TEST-ONLY load and failure test (increment 28, ADR-0028). Not part of the normal build: it runs
 * with {@code -Dsurefire.excludedGroups=sandbox -Dgroups=performance}, from the manual
 * "performance" workflow, which uploads the report it writes.
 *
 * <p>Seeds a synthetic book (50,000 members with savings accounts, 20,000 active loans) into a
 * PostgreSQL 17 container, then drives the real application (Spring Security, services, JDBC,
 * RLS) in-process through MockMvc. HTTP transport and TLS are not measured. Every phase checks
 * correctness as well as time: exact balances, one posting per receipt, a clean reconciliation.
 */
@Tag("performance")
@Testcontainers
@SpringBootTest
class PerformanceLoadTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final int MEMBERS = Integer.getInteger("perf.members", 50_000);
    static final int LOANS = Integer.getInteger("perf.loans", 20_000);
    static final int OPS_PER_WORKER = Integer.getInteger("perf.opsPerWorker", 600);
    static final int TELLERS = 4;
    static final int C2B_WORKERS = 4;
    static final int HOT_ACCOUNTS = 400;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql")
        .withSharedMemorySize(512L * 1024 * 1024);

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
    static class NoRealTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("test decoder accepts no tokens");
            };
        }
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;
    final StringBuilder report = new StringBuilder();

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor staff(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static JwtRequestPostProcessor integration() {
        return jwt().jwt(j -> j.subject("svc-integration")).authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));
    }

    record Call(int status, String body, long nanos) { }

    Call post(String path, String body, JwtRequestPostProcessor who) throws Exception {
        long start = System.nanoTime();
        MockHttpServletResponse r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(path).with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        return new Call(r.getStatus(), r.getContentAsString(), System.nanoTime() - start);
    }

    @Test
    void loadAndFailure() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        line("# Load and failure test report (TEST-ONLY data)");
        line("");
        line("Generated by `PerformanceLoadTest` (increment 28). PostgreSQL 17 in a container; the application runs");
        line("in-process (MockMvc), so the figures include security, services, JDBC and the database, not HTTP.");
        line("Runner: " + Runtime.getRuntime().availableProcessors() + " CPUs, JVM max heap "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB, Java " + System.getProperty("java.version") + ".");
        line("");
        try {
            seed();
            concurrentTellerAndMpesaTraffic();
            duplicateDeliveryBurst();
            endOfDayWithFailureAndResume();
        } finally {
            writeReport();
        }
    }

    // ------------------------------------------------------------------------------------- seed

    void seed() throws Exception {
        String sql = new String(PerformanceLoadTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8)
            .replace("{T}", T).replace("{NS}", Integer.toString(MEMBERS)).replace("{NL}", Integer.toString(LOANS));
        Path file = Files.createTempFile("perf-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/perf-seed.sql");
        long start = System.nanoTime();
        var result = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d",
                PG.getDatabaseName(), "-f", "/tmp/perf-seed.sql");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(0, result.getExitCode(), result.getStderr());
        JdbcTemplate db = owner();
        int accounts = db.queryForObject("SELECT count(*) FROM savings.deposit_account WHERE tenant_id = ?::uuid", Integer.class, T);
        int loans = db.queryForObject("SELECT count(*) FROM lending.loan WHERE tenant_id = ?::uuid AND status = 'ACTIVE'",
                Integer.class, T);
        int installments = db.queryForObject("SELECT count(*) FROM lending.schedule_installment WHERE tenant_id = ?::uuid",
                Integer.class, T);
        assertEquals(MEMBERS, accounts);
        assertEquals(LOANS, loans);
        line("## 1. Synthetic book");
        line("");
        line("| Item | Count |");
        line("|---|---:|");
        line("| Members with savings accounts | " + accounts + " |");
        line("| Active loans | " + loans + " |");
        line("| Schedule instalments | " + installments + " |");
        line("| Seed time (psql, ledger checks at commit) | " + ms + " ms |");
        line("| Database size after seed | " + db.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))",
                String.class) + " |");
        line("");
    }

    // ------------------------------------------------------------------------- concurrent traffic

    Map<String, String> sessions = new HashMap<>();
    List<String> hot;
    Map<String, BigDecimal> before;
    final Map<String, BigDecimal> expectedDelta = new ConcurrentHashMap<>();
    final Map<Integer, BigDecimal> tillCash = new ConcurrentHashMap<>();
    final List<Long> depositNanos = new CopyOnWriteArrayList<>();
    final List<Long> withdrawalNanos = new CopyOnWriteArrayList<>();
    final List<Long> c2bNanos = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();

    Map<String, BigDecimal> balances(List<String> accounts) {
        Map<String, BigDecimal> m = new HashMap<>();
        owner().query("""
                SELECT a.account_number, COALESCE(sum(CASE WHEN l.side = 'C' THEN l.amount ELSE -l.amount END), 0)
                  FROM savings.deposit_account a
                  LEFT JOIN ledger.journal_line l ON l.tenant_id = a.tenant_id AND l.subledger_account_id = a.account_id
                 WHERE a.tenant_id = ?::uuid AND a.account_number = ANY (?)
                 GROUP BY a.account_number
                """, rs -> {
            m.put(rs.getString(1), rs.getBigDecimal(2));
        }, T, accounts.toArray(new String[0]));
        return m;
    }

    void concurrentTellerAndMpesaTraffic() throws Exception {
        hot = owner().queryForList("""
                SELECT a.account_number FROM savings.deposit_account a
                 WHERE a.tenant_id = ?::uuid AND a.branch_code = 'NBO01'
                   AND (SELECT sum(CASE WHEN l.side = 'C' THEN l.amount ELSE -l.amount END) FROM ledger.journal_line l
                         WHERE l.tenant_id = a.tenant_id AND l.subledger_account_id = a.account_id) >= 5000
                 ORDER BY md5(a.account_number) LIMIT ?
                """, String.class, T, HOT_ACCOUNTS);
        assertEquals(HOT_ACCOUNTS, hot.size());
        before = balances(hot);
        for (int i = 1; i <= TELLERS; i++) {
            Call opened = post("/api/v1/savings/till-sessions", "{\"tillCode\":\"P-T" + i + "\"}", staff("perf-teller-" + i, "TELLER"));
            assertEquals(200, opened.status(), opened.body());
            sessions.put("perf-teller-" + i, JsonPath.read(opened.body(), "$.sessionId"));
            tillCash.put(i, BigDecimal.ZERO.setScale(2));
        }
        ExecutorService pool = Executors.newFixedThreadPool(TELLERS + C2B_WORKERS);
        List<Future<?>> work = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 1; i <= TELLERS; i++) {
            int teller = i;
            work.add(pool.submit(() -> tellerWorker(teller)));
        }
        for (int i = 1; i <= C2B_WORKERS; i++) {
            int worker = i;
            work.add(pool.submit(() -> c2bWorker(worker)));
        }
        for (Future<?> f : work) {
            f.get();
        }
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        // Exact balances: every accepted operation is in the ledger, nothing else is.
        Map<String, BigDecimal> after = balances(hot);
        int mismatched = 0;
        for (String a : hot) {
            BigDecimal want = before.get(a).add(expectedDelta.getOrDefault(a, BigDecimal.ZERO));
            if (want.compareTo(after.get(a)) != 0) {
                mismatched++;
                errors.add("balance " + a + " expected " + want + " found " + after.get(a));
            }
        }
        int total = depositNanos.size() + withdrawalNanos.size() + c2bNanos.size();
        line("## 2. Concurrent teller and M-Pesa traffic");
        line("");
        line(TELLERS + " tellers (each on their own till) and " + C2B_WORKERS + " M-Pesa C2B workers, all on the same "
                + HOT_ACCOUNTS + " Nairobi accounts at once.");
        line("");
        line("| Operation | Count | p50 ms | p95 ms | p99 ms | max ms |");
        line("|---|---:|---:|---:|---:|---:|");
        line(row("Teller deposit", depositNanos));
        line(row("Teller withdrawal", withdrawalNanos));
        line(row("M-Pesa C2B confirmation", c2bNanos));
        line("");
        line("Wall time " + wallMs + " ms for " + total + " postings: " + (total * 1000L / Math.max(1, wallMs))
                + " postings per second. Errors: " + errors.size() + ". Accounts with a balance different from"
                + " the sum of accepted operations: " + mismatched + ".");
        line("");
        if (!errors.isEmpty()) {
            line("First errors:");
            errors.stream().limit(10).forEach(e -> line("- " + e));
            line("");
        }
        assertTrue(errors.isEmpty(), () -> "errors: " + errors.stream().limit(5).toList());
        assertEquals(0, mismatched);

        // The tellers count exactly what the system expects and close their tills.
        for (int i = 1; i <= TELLERS; i++) {
            String teller = "perf-teller-" + i;
            Call closed = post("/api/v1/savings/till-sessions/" + sessions.get(teller) + "/close",
                    "{\"countedAmount\":\"" + tillCash.get(i).toPlainString() + "\"}", staff(teller, "TELLER"));
            assertEquals(200, closed.status(), closed.body());
            assertEquals("CLOSED", JsonPath.read(closed.body(), "$.status"), closed.body());
        }
    }

    void tellerWorker(int teller) {
        Random r = new Random(1000 + teller);
        var who = staff("perf-teller-" + teller, "TELLER");
        for (int n = 0; n < OPS_PER_WORKER; n++) {
            String account = hot.get(r.nextInt(hot.size()));
            boolean withdraw = n % 4 == 3 && tillCash.get(teller).compareTo(new BigDecimal("50")) >= 0;
            BigDecimal amount = withdraw ? new BigDecimal("50.00") : BigDecimal.valueOf(10_000 + r.nextInt(490_000), 2);
            String key = "perf-t" + teller + "-" + n;
            String body = """
                {"idempotencyKey":"%s","accountNumber":"%s","kind":"%s","amount":"%s","narrative":"perf"}
                """.formatted(key, account, withdraw ? "WITHDRAWAL" : "DEPOSIT", amount.toPlainString());
            try {
                Call c = post("/api/v1/savings/teller/transactions", body, who);
                if (c.status() != 200 || !"POSTED".equals(JsonPath.read(c.body(), "$.status"))) {
                    errors.add("teller " + key + " " + c.status() + " " + c.body());
                    continue;
                }
                (withdraw ? withdrawalNanos : depositNanos).add(c.nanos());
                expectedDelta.merge(account, withdraw ? amount.negate() : amount, BigDecimal::add);
                tillCash.merge(teller, withdraw ? amount.negate() : amount, BigDecimal::add);
            } catch (Exception e) {
                errors.add("teller " + key + " " + e);
            }
        }
    }

    static String confirmation(String transId, String account, BigDecimal amount) {
        return """
            {"tenantId":"%s","transId":"%s","shortCode":"600993","amount":"%s","currency":"KES","billRef":"%s",
             "msisdnMasked":"2547*****000","transTime":"2026-11-28T10:15:00+03:00"}
            """.formatted(T, transId, amount.toPlainString(), account);
    }

    void c2bWorker(int worker) {
        Random r = new Random(2000 + worker);
        for (int n = 0; n < OPS_PER_WORKER; n++) {
            String account = hot.get(r.nextInt(hot.size()));
            BigDecimal amount = BigDecimal.valueOf(5_000 + r.nextInt(1_995_000), 2);
            String transId = "TPERF%d%06d".formatted(worker, n);
            try {
                Call c = post("/api/v1/payments/mpesa/c2b", confirmation(transId, account, amount), integration());
                if (c.status() != 200 || !"APPLIED".equals(JsonPath.read(c.body(), "$.outcome"))) {
                    errors.add("c2b " + transId + " " + c.status() + " " + c.body());
                    continue;
                }
                c2bNanos.add(c.nanos());
                expectedDelta.merge(account, amount, BigDecimal::add);
            } catch (Exception e) {
                errors.add("c2b " + transId + " " + e);
            }
        }
    }

    // ---------------------------------------------------------------------- duplicate delivery

    void duplicateDeliveryBurst() throws Exception {
        String account = hot.get(0);
        BigDecimal start = balances(List.of(account)).get(account);
        String body = confirmation("TDUPBURST01", account, new BigDecimal("777.00"));
        int copies = 16;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        List<Future<Call>> calls = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            calls.add(pool.submit(() -> post("/api/v1/payments/mpesa/c2b", body, integration())));
        }
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger firstTime = new AtomicInteger();
        List<String> paymentIds = new ArrayList<>();
        for (Future<Call> f : calls) {
            Call c = f.get();
            if (c.status() == 200) {
                ok.incrementAndGet();
                paymentIds.add(JsonPath.read(c.body(), "$.paymentId"));
                if (!(Boolean) JsonPath.read(c.body(), "$.replayed")) {
                    firstTime.incrementAndGet();
                }
            } else {
                assertEquals(409, c.status(), c.body());
                assertEquals("DUPLICATE_IN_FLIGHT", JsonPath.read(c.body(), "$.code"), c.body());
                inFlight.incrementAndGet();
            }
        }
        pool.shutdown();
        // A 409 tells the sender to retry; the retry returns the original payment.
        for (int i = 0; i < inFlight.get(); i++) {
            Call retry = post("/api/v1/payments/mpesa/c2b", body, integration());
            assertEquals(200, retry.status(), retry.body());
            assertEquals(Boolean.TRUE, JsonPath.read(retry.body(), "$.replayed"));
            paymentIds.add(JsonPath.read(retry.body(), "$.paymentId"));
        }
        // A late redelivery, after the business day has moved on, is still a replay (checked again after EOD).
        assertEquals(1, firstTime.get());
        assertEquals(1, paymentIds.stream().distinct().count());
        assertEquals(1, owner().queryForObject(
                "SELECT count(*) FROM payments.provider_payment WHERE tenant_id = ?::uuid AND provider_ref = 'TDUPBURST01'",
                Integer.class, T));
        assertEquals(0, new BigDecimal("777.00").compareTo(balances(List.of(account)).get(account).subtract(start)));
        line("## 3. Duplicate delivery of one M-Pesa receipt");
        line("");
        line(copies + " copies of the same TransID sent at the same moment: " + firstTime.get() + " posted, "
                + (ok.get() - firstTime.get()) + " answered as replays, " + inFlight.get()
                + " told to retry (409 DUPLICATE_IN_FLIGHT) and answered as replays on retry. One provider payment,"
                + " one ledger posting, balance up by exactly 777.00.");
        line("");
    }

    // ------------------------------------------------------------------------------ end of day

    record EodRun(Call call, Map<String, Object> summary) { }

    @SuppressWarnings("unchecked")
    EodRun eod() throws Exception {
        Call c = post("/api/v1/end-of-day/run", "", staff("perf-ops", "OPERATIONS"));
        Map<String, Object> s = c.status() == 200 ? JsonPath.read(c.body(), "$") : Map.of();
        return new EodRun(c, s);
    }

    void endOfDayWithFailureAndResume() throws Exception {
        JdbcTemplate db = owner();
        line("## 4. End of day over the whole book");
        line("");
        // Failure injection: the database refuses the arrears snapshot of the loan in the middle of the book,
        // as a crash or a constraint failure would. Everything before it is committed loan by loan.
        String victim = db.queryForObject("""
                SELECT loan_number FROM lending.loan WHERE tenant_id = ?::uuid ORDER BY loan_number OFFSET ? LIMIT 1
                """, String.class, T, LOANS / 2);
        db.execute("""
            CREATE FUNCTION lending.perf_inject_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF NEW.loan_id = (SELECT loan_id FROM lending.loan WHERE loan_number = '%s' AND tenant_id = NEW.tenant_id) THEN
                RAISE EXCEPTION 'PERF_INJECTED_FAILURE: simulated failure while processing loan %s';
              END IF;
              RETURN NEW;
            END $$;
            CREATE TRIGGER perf_inject_failure BEFORE INSERT ON lending.arrears_snapshot
              FOR EACH ROW EXECUTE FUNCTION lending.perf_inject_failure();
            """.formatted(victim, victim));
        long failStart = System.nanoTime();
        EodRun failed = eod();
        long failMs = (System.nanoTime() - failStart) / 1_000_000;
        assertTrue(failed.call().status() >= 400, failed.call().body());
        assertEquals("FAILED", db.queryForObject(
                "SELECT status FROM ledger.eod_run WHERE tenant_id = ?::uuid AND business_date = '2026-11-28'", String.class, T));
        int doneBefore = db.queryForObject(
                "SELECT count(*) FROM lending.arrears_snapshot WHERE tenant_id = ?::uuid AND as_of = '2026-11-28'", Integer.class, T);
        assertEquals("2026-11-28", db.queryForObject(
                "SELECT current_date_::text FROM tenancy.business_date WHERE tenant_id = ?::uuid", String.class, T));
        db.execute("DROP TRIGGER perf_inject_failure ON lending.arrears_snapshot; DROP FUNCTION lending.perf_inject_failure()");

        line("Failure injection: the arrears snapshot of loan " + victim + " (half-way through the book) was made to fail.");
        line("The run stopped after " + failMs + " ms with HTTP " + failed.call().status() + ", was recorded FAILED, and the"
                + " business date did not move. " + doneBefore + " loans had already been processed and committed.");
        line("");
        line("| Run | HTTP | Loans | Savings accrued | Accounts credited | Reconciliation differences | Loans ms | Accrual ms | Crediting ms | Close ms | Total ms |");
        line("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        List<EodRun> runs = new ArrayList<>();
        String[] labels = {"28 Nov (resumed after failure)", "29 Nov", "30 Nov (month end: interest credited)"};
        for (String label : labels) {
            EodRun run = eod();
            assertEquals(200, run.call().status(), run.call().body());
            runs.add(run);
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) run.summary().get("timings");
            line("| " + label + " | " + run.call().status() + " | " + run.summary().get("loansProcessed") + " | "
                    + run.summary().get("accountsAccrued") + " | " + run.summary().get("accountsCredited") + " | "
                    + run.summary().get("reconciliationDifferences") + " | " + t.get("loansMs") + " | "
                    + t.get("savingsAccrualMs") + " | " + t.get("interestCreditMs") + " | " + t.get("closeMs") + " | "
                    + t.get("totalMs") + " |");
            assertEquals(0, ((Number) run.summary().get("reconciliationDifferences")).intValue(), run.call().body());
        }
        line("");
        // The resumed run picked up exactly where the failed one stopped: one snapshot per loan, never two.
        assertEquals(LOANS - doneBefore, ((Number) runs.get(0).summary().get("loansProcessed")).intValue()
                + ((Number) runs.get(0).summary().get("loansClosed")).intValue());
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM (SELECT loan_id, as_of FROM lending.arrears_snapshot WHERE tenant_id = ?::uuid
                                       GROUP BY loan_id, as_of HAVING count(*) > 1) d
                """, Integer.class, T));
        assertEquals(LOANS, db.queryForObject(
                "SELECT count(*) FROM lending.arrears_snapshot WHERE tenant_id = ?::uuid AND as_of = '2026-11-28'", Integer.class, T));
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM (SELECT idempotency_key FROM ledger.journal_entry WHERE tenant_id = ?::uuid
                                       GROUP BY idempotency_key HAVING count(*) > 1) d
                """, Integer.class, T));
        assertTrue(((Number) runs.get(2).summary().get("accountsCredited")).intValue() > 0);
        BigDecimal trial = db.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN side = 'D' THEN amount ELSE -amount END), 0) FROM ledger.journal_line
                 WHERE tenant_id = ?::uuid
                """, BigDecimal.class, T);
        assertEquals(0, trial.signum());
        assertEquals("2026-12-01", db.queryForObject(
                "SELECT current_date_::text FROM tenancy.business_date WHERE tenant_id = ?::uuid", String.class, T));

        // A receipt redelivered days later is still one posting.
        Call late = post("/api/v1/payments/mpesa/c2b", confirmation("TDUPBURST01", hot.get(0), new BigDecimal("777.00")),
                integration());
        assertEquals(200, late.status(), late.body());
        assertEquals(Boolean.TRUE, JsonPath.read(late.body(), "$.replayed"));

        line("After the three runs: one arrears snapshot per loan per day, no duplicate ledger postings, a trial"
                + " balance of exactly zero and the business date on 1 Dec 2026. The duplicate receipt from section 3,"
                + " redelivered after the month end, was answered as a replay.");
        line("");
        line("| Ledger after the test | Count |");
        line("|---|---:|");
        line("| Journal entries | " + db.queryForObject("SELECT count(*) FROM ledger.journal_entry WHERE tenant_id = ?::uuid",
                Long.class, T) + " |");
        line("| Journal lines | " + db.queryForObject("SELECT count(*) FROM ledger.journal_line WHERE tenant_id = ?::uuid",
                Long.class, T) + " |");
        line("| Database size | " + db.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))",
                String.class) + " |");
        line("");
    }

    // --------------------------------------------------------------------------------- report

    static String row(String label, List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return "| " + label + " | 0 | | | | |";
        }
        return "| " + label + " | " + sorted.size() + " | " + pct(sorted, 50) + " | " + pct(sorted, 95) + " | "
                + pct(sorted, 99) + " | " + ms(sorted.get(sorted.size() - 1)) + " |";
    }

    static String pct(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return ms(sorted.get(Math.max(0, index)));
    }

    static String ms(long nanos) {
        return BigDecimal.valueOf(nanos / 10_000, 2).toPlainString(); // 0.01 ms resolution
    }

    void line(String s) {
        report.append(s).append('\n');
    }

    void writeReport() throws IOException {
        Path out = Path.of("target", "performance-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println("----- BEGIN PERFORMANCE REPORT -----");
        System.out.print(report);
        System.out.println("----- END PERFORMANCE REPORT -----");
    }
}
