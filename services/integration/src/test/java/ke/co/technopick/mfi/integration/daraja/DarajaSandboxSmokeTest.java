package ke.co.technopick.mfi.integration.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke test against Safaricom's Daraja SANDBOX (no real money). Runs only in the manual
 * {@code daraja-sandbox} GitHub workflow, which supplies credentials from repository secrets;
 * excluded from every other build and skipped when the secrets are absent. Never prints secrets.
 *
 * <p>Checks what can be checked without a public callback endpoint: OAuth, STK Push request with
 * our password construction, STK status query, and (optionally) a B2C request with our
 * SecurityCredential encryption. Result callbacks go to {@code DARAJA_SANDBOX_CALLBACK_BASE_URL}
 * and are not received by this test.
 */
@Tag("sandbox")
class DarajaSandboxSmokeTest {

    /** The only host this test may call: Safaricom's sandbox. Production is refused. */
    static final String SANDBOX = "https://sandbox.safaricom.co.ke";

    static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static DarajaProperties props(List<DarajaProperties.B2cAccount> b2c, String certificate) {
        return new DarajaProperties(List.of(), List.of(), null, false, 1000, 10, null, SANDBOX,
                env("DARAJA_SANDBOX_CONSUMER_KEY"), env("DARAJA_SANDBOX_CONSUMER_SECRET"),
                env("DARAJA_SANDBOX_CALLBACK_BASE_URL"), false, false, b2c, certificate, false);
    }

    @Test
    void stkPushRequestAndQuery() throws InterruptedException {
        assumeTrue(env("DARAJA_SANDBOX_CONSUMER_KEY") != null && env("DARAJA_SANDBOX_PASSKEY") != null
                && env("DARAJA_SANDBOX_CALLBACK_BASE_URL") != null, "sandbox secrets not provided");
        String shortCode = env("DARAJA_SANDBOX_SHORTCODE") == null ? "174379" : env("DARAJA_SANDBOX_SHORTCODE");
        String msisdn = env("DARAJA_SANDBOX_TEST_MSISDN") == null ? "254708374149" : env("DARAJA_SANDBOX_TEST_MSISDN");
        DarajaProperties.Paybill paybill = new DarajaProperties.Paybill("unused", UUID.randomUUID(), shortCode,
                env("DARAJA_SANDBOX_PASSKEY"));
        DarajaClient client = new DarajaClient(props(List.of(), null));
        String requestId = UUID.randomUUID().toString();

        DarajaClient.StkOutcome sent = client.stkPush(paybill, msisdn, "1", "SMOKETEST", "Smoke test",
                env("DARAJA_SANDBOX_CALLBACK_BASE_URL") + "/daraja/stk/sandbox/callback/" + requestId);
        System.out.println("STK Push: " + sent.kind() + " code=" + sent.code() + " desc=" + sent.description());
        assertEquals(DarajaClient.Kind.ACCEPTED, sent.kind(), "sandbox refused the STK Push: " + sent.description());
        assertNotNull(sent.checkoutRequestId());

        Thread.sleep(5000);
        DarajaClient.QueryOutcome q = client.stkQuery(paybill, sent.checkoutRequestId());
        System.out.println("STK query: " + q.kind() + " resultCode=" + q.resultCode() + " desc=" + q.resultDesc());
        assertNotEquals(DarajaClient.QueryKind.ERROR, q.kind(), "sandbox STK query failed: " + q.resultDesc());
    }

    @Test
    void b2cRequest() {
        assumeTrue(env("DARAJA_SANDBOX_CONSUMER_KEY") != null && env("DARAJA_SANDBOX_B2C_INITIATOR_NAME") != null
                && env("DARAJA_SANDBOX_B2C_INITIATOR_PASSWORD") != null && env("DARAJA_SANDBOX_CERTIFICATE_PEM") != null
                && env("DARAJA_SANDBOX_CALLBACK_BASE_URL") != null, "sandbox B2C secrets not provided");
        String shortCode = env("DARAJA_SANDBOX_B2C_SHORTCODE") == null ? "600999" : env("DARAJA_SANDBOX_B2C_SHORTCODE");
        String msisdn = env("DARAJA_SANDBOX_TEST_MSISDN") == null ? "254708374149" : env("DARAJA_SANDBOX_TEST_MSISDN");
        DarajaProperties.B2cAccount account = new DarajaProperties.B2cAccount("unused", UUID.randomUUID(), shortCode,
                env("DARAJA_SANDBOX_B2C_INITIATOR_NAME"), env("DARAJA_SANDBOX_B2C_INITIATOR_PASSWORD"));
        DarajaClient client = new DarajaClient(props(List.of(account), env("DARAJA_SANDBOX_CERTIFICATE_PEM")));
        String id = UUID.randomUUID().toString();
        String base = env("DARAJA_SANDBOX_CALLBACK_BASE_URL") + "/daraja/b2c/sandbox";

        DarajaClient.B2cOutcome sent = client.b2c(account, id, msisdn, "10", "Sandbox smoke test", "SMOKETEST",
                base + "/result/" + id, base + "/timeout/" + id);
        System.out.println("B2C: " + sent.kind() + " code=" + sent.code() + " desc=" + sent.description());
        assertEquals(DarajaClient.Kind.ACCEPTED, sent.kind(), "sandbox refused the B2C request: " + sent.description());
        assertNotNull(sent.conversationId());
    }
}
