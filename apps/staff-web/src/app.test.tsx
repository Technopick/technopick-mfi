import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApiError, createApi } from "./api/client";
import { sessionFromToken } from "./auth/session";
import { loadConfig } from "./config";
import { formatAmount, formatMoney, isAmount } from "./money";
import { fakeApi, renderAt, session } from "./test-utils";

function jwt(payload: object): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "none" })}.${b64(payload)}.sig`;
}

const application = {
  applicationId: "a-1", customerRef: "CUST-500", productCode: "BIASHARA", branchCode: "NBO01",
  principal: "100000.00", currency: "KES", termMonths: 12, firstDueDate: "2026-12-18", purpose: "Restock",
  status: "SUBMITTED", submittedBy: "user:officer-1", submittedAt: "2026-11-18T10:00:00Z",
  quote: {
    productCode: "BIASHARA", productVersion: 1, interestMethod: "REDUCING_BALANCE", annualRate: "0.24",
    principal: "100000.00", termMonths: 12, upfrontFee: "2000.00", netDisbursement: "98000.00",
    totalInterest: "13471.52", totalRepayable: "113471.52",
    installments: [{ number: 1, dueDate: "2026-12-18", principal: "7455.96", interest: "2000.00", total: "9455.96", closingBalance: "92544.04" }],
  },
};

const noTill = {
  "GET /api/v1/savings/till-sessions/mine": () => {
    throw new ApiError({ status: 404, title: "Not Found", detail: "no till open" });
  },
  "GET /api/v1/savings/tills": () => [],
};

const SCHEDULE_OFF = { enabled: false, runAt: "21:00", timeZone: "Africa/Nairobi", overrunMinutes: 60, maxAttempts: 3,
  retryMinutes: 15, maxCatchUpDays: 3, nextCutOff: "2026-11-28T21:00:00+03:00" };
const SCHEDULE_ON = { ...SCHEDULE_OFF, enabled: true, updatedBy: "user:sup-1", updatedAt: "2026-11-20T09:00:00Z" };

describe("money", () => {
  it("formats decimal strings without floating point", () => {
    expect(formatAmount("1234567.5")).toBe("1,234,567.50");
    expect(formatAmount("0.1")).toBe("0.10");
    expect(formatAmount("9007199254740993.99")).toBe("9,007,199,254,740,993.99"); // beyond float precision
    expect(formatMoney("98000.00")).toBe("KES 98,000.00");
  });
  it("accepts only plain amounts with at most two decimals", () => {
    expect(isAmount("50000")).toBe(true);
    expect(isAmount("50000.50")).toBe(true);
    for (const bad of ["1.005", "1e3", "-1", "1,000", "", "abc"]) expect(isAmount(bad)).toBe(false);
  });
});

describe("session", () => {
  it("reads roles from a nested claim and the tenant", () => {
    const s = sessionFromToken(jwt({ sub: "u-1", tenant_id: "t-9", realm_access: { roles: ["TELLER"] } }), "realm_access.roles");
    expect(s.roles).toEqual(["TELLER"]);
    expect(s.tenantId).toBe("t-9");
  });
});

describe("api client", () => {
  it("sends the bearer token and surfaces problem details", async () => {
    const fetchFn = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer tok");
      return new Response(JSON.stringify({ title: "Conflict", detail: "application is already APPROVED", code: "X" }), { status: 409 });
    });
    const api = createApi("", () => "tok", fetchFn as unknown as typeof fetch);
    const err = await api.post("/x", {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).problem).toMatchObject({ status: 409, title: "Conflict", detail: "application is already APPROVED" });
  });
});

describe("config", () => {
  const respond = (body: object) => (async () => new Response(JSON.stringify(body))) as unknown as typeof fetch;
  it("requires an identity provider over https", async () => {
    await expect(loadConfig(respond({ oidcClientId: "x" }))).rejects.toThrow(/oidcAuthority/);
    await expect(loadConfig(respond({ oidcAuthority: "http://idp.example", oidcClientId: "x" }))).rejects.toThrow(/https/);
    await expect(loadConfig(respond({ oidcAuthority: "https://idp.example", oidcClientId: "x" }))).resolves.toMatchObject({ rolesClaim: "roles" });
  });
});

describe("screens", () => {
  it("lets a different manager approve, and posts to the approve endpoint", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/lending/applications/a-1": () => application,
      "POST /api/v1/lending/applications/a-1/approve": () => ({ ...application, status: "APPROVED", loanNumber: "LN26-0000001" }),
    });
    renderAt("/applications/a-1", session("manager-1", "BRANCH_MANAGER"), api);
    expect(await screen.findByText("KES 98,000.00")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: /approve and create loan/i }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/approve"))).toBe(true));
  });

  it("hides approval from the submitter (four eyes) and requires a decline reason", async () => {
    const { api } = fakeApi({ "GET /api/v1/lending/applications/a-1": () => application });
    renderAt("/applications/a-1", session("officer-1", "LOAN_OFFICER", "BRANCH_MANAGER"), api);
    expect(await screen.findByText(/a colleague must decide/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
  });

  it("disables decline until a reason is given", async () => {
    const { api } = fakeApi({ "GET /api/v1/lending/applications/a-1": () => application });
    renderAt("/applications/a-1", session("manager-1", "BRANCH_MANAGER"), api);
    const decline = await screen.findByRole("button", { name: /decline/i });
    expect((decline as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(/reason/i), "Existing arrears");
    expect((decline as HTMLButtonElement).disabled).toBe(false);
  });

  it("shows only the navigation a teller may use", async () => {
    const { api } = fakeApi({});
    renderAt("/", session("teller-1", "TELLER"), api);
    const nav = await screen.findByRole("navigation", { name: "Main" });
    expect(within(nav).getAllByRole("link").map((a) => a.textContent)).toEqual(["Worklist", "Customers", "Teller", "Loan schedule"]);
  });

  it("validates the amount locally and sends it as a string with an idempotency key", async () => {
    const { api, calls } = fakeApi({
      "POST /api/v1/lending/applications": () => ({ applicationId: "a-2", status: "SUBMITTED", replayed: false }),
      "GET /api/v1/lending/applications/a-2": () => ({ ...application, applicationId: "a-2" }),
    });
    renderAt("/applications/new", session("officer-1", "LOAN_OFFICER"), api);
    await userEvent.type(await screen.findByLabelText(/customer number/i), "CU26-0000001");
    await userEvent.type(screen.getByLabelText(/product code/i), "BIASHARA");
    await userEvent.type(screen.getByLabelText(/branch code/i), "NBO01");
    await userEvent.type(screen.getByLabelText(/amount/i), "1000.005");
    await userEvent.type(screen.getByLabelText(/purpose/i), "Stock");
    await userEvent.click(screen.getByRole("button", { name: /submit for approval/i }));
    expect(await screen.findByText(/at most 2 decimal places/i)).toBeTruthy();
    expect(calls).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText(/amount/i));
    await userEvent.type(screen.getByLabelText(/amount/i), "50000");
    await userEvent.click(screen.getByRole("button", { name: /submit for approval/i }));
    await waitFor(() => expect(calls[0]?.method).toBe("POST"));
    const body = calls[0]?.body as Record<string, unknown>;
    expect(body.principal).toBe("50000");
    expect(typeof body.idempotencyKey).toBe("string");
  });

  it("shows the API's reason when an action is refused", async () => {
    const { api } = fakeApi({
      "GET /api/v1/payments/mpesa/b2c/disbursements": () => { throw new ApiError({ status: 403, title: "Not allowed" }); },
    });
    renderAt("/disbursements", session("manager-1", "BRANCH_MANAGER"), api);
    expect((await screen.findByRole("alert")).textContent).toContain("Not allowed");
  });

  it("cannot register a customer without recorded consent, and hides verification from the registrar", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/customers/CU26-0000001": () => ({
        customerNumber: "CU26-0000001", firstName: "Achieng", lastName: "Otieno", status: "PENDING_VERIFICATION",
        createdBy: "user:officer-1", idType: "NATIONAL_ID", idNumber: "12345678", phoneNumber: "254712345678",
      }),
    });
    renderAt("/customers/new", session("officer-1", "LOAN_OFFICER"), api);
    const register = await screen.findByRole("button", { name: /register/i });
    expect((register as HTMLButtonElement).disabled).toBe(true);
    await userEvent.click(screen.getByRole("checkbox"));
    expect((register as HTMLButtonElement).disabled).toBe(false);
    expect(calls).toHaveLength(0);
  });

  it("lets a different manager verify a customer's identity with a note", async () => {
    const customer = {
      customerNumber: "CU26-0000001", firstName: "Achieng", lastName: "Otieno", status: "PENDING_VERIFICATION",
      createdBy: "user:officer-1", idType: "NATIONAL_ID", idNumber: "12345678", phoneNumber: "254712345678",
    };
    const { api, calls } = fakeApi({
      "GET /api/v1/customers/CU26-0000001": () => customer,
      "POST /api/v1/customers/CU26-0000001/verify": () => ({ ...customer, status: "ACTIVE" }),
      "GET /api/v1/savings/accounts?customerNumber=CU26-0000001": () => [],
      "GET /api/v1/customers/CU26-0000001/member-pin": () => ({ customerNumber: "CU26-0000001", status: "NONE", failedAttempts: 0,
        maxAttempts: 3, setups: [], recentEvents: [] }),
    });
    renderAt("/customers/CU26-0000001", session("manager-1", "BRANCH_MANAGER"), api);
    const verify = await screen.findByRole("button", { name: /verify identity/i });
    expect((verify as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(/what was checked/i), "National ID sighted at branch");
    await userEvent.click(verify);
    await waitFor(() => expect(calls.some((c) => c.path.endsWith("/verify"))).toBe(true));
  });

  it("tells the teller not to hand over cash while a large withdrawal waits for approval", async () => {
    const account = { accountNumber: "2000000014", customerNumber: "CU26-0000001", customerName: "Achieng Otieno",
      productName: "Savings", currency: "KES", status: "ACTIVE", ledgerBalance: "100000.00", heldAmount: "0.00",
      availableBalance: "100000.00" };
    const { api, calls } = fakeApi({
      "GET /api/v1/savings/accounts/2000000014": () => account,
      "GET /api/v1/savings/accounts/2000000014/statement": () => ({ from: "2026-10-20", to: "2026-11-19", currency: "KES",
        openingBalance: "0.00", closingBalance: "100000.00", totalDebits: "0.00", totalCredits: "100000.00", lines: [] }),
      "POST /api/v1/savings/teller/transactions": (body) => ({ ...(body as object), status: "PENDING_APPROVAL", currency: "KES" }),
    }, 30); // real latency, so a refresh is visible to the UI
    renderAt("/accounts/2000000014", session("teller-1", "TELLER"), api);
    await userEvent.selectOptions(await screen.findByLabelText("Transaction"), "WITHDRAWAL");
    await userEvent.type(screen.getByLabelText(/amount \(KES\)/i), "60000");
    await userEvent.click(screen.getByRole("button", { name: "Post" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/do not pay out/i);
    // The confirmation survives the balance refresh that follows a posting.
    await waitFor(() => expect(calls.filter((c) => c.path === "/api/v1/savings/accounts/2000000014").length).toBe(2));
    await new Promise((r) => setTimeout(r, 100)); // let the refresh complete
    expect(screen.getByRole("status").textContent).toMatch(/do not pay out/i);
    const post = calls.find((c) => c.method === "POST");
    expect((post?.body as Record<string, unknown>).amount).toBe("60000");
  });

  it("hides approval of a cash transaction from the teller who captured it", async () => {
    const { api } = fakeApi({
      "GET /api/v1/savings/teller/transactions": () => [{ txnId: "t-1", accountNumber: "2000000014", kind: "WITHDRAWAL",
        amount: "60000.00", currency: "KES", narrative: "Cash withdrawal", status: "PENDING_APPROVAL",
        requestedBy: "user:sup-teller" }],
      ...noTill,
      "GET /api/v1/savings/cash-transfers": () => [],
      "GET /api/v1/savings/till-sessions/closing": () => [],
    });
    renderAt("/teller", session("sup-teller", "TELLER", "SUPERVISOR"), api);
    expect(await screen.findByText(/a colleague must decide/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("lets a teller open a till, ask the vault for cash and count out with a difference", async () => {
    let state: "none" | "open" | "closing" = "none";
    const open = { sessionId: "s-1", tillCode: "NBO01-T1", branchCode: "NBO01", currency: "KES", teller: "user:teller-1",
      businessDate: "2026-11-19", status: "OPEN", expectedCash: "50000.00", pendingTransactions: 0, pendingTransfers: 0 };
    const { api, calls } = fakeApi({
      "GET /api/v1/savings/teller/transactions": () => [],
      "GET /api/v1/savings/till-sessions/mine": () => {
        if (state === "none") throw new ApiError({ status: 404, title: "Not Found", detail: "no till open" });
        return state === "open" ? open : { ...open, status: "CLOSING", countedAmount: "49900.00", variance: "-100.00" };
      },
      "GET /api/v1/savings/tills": () => [{ tillCode: "NBO01-T1", branchCode: "NBO01", currency: "KES", active: true },
        { tillCode: "NBO01-T2", branchCode: "NBO01", currency: "KES", active: true, openedBy: "user:other", sessionStatus: "OPEN" }],
      "POST /api/v1/savings/till-sessions": () => { state = "open"; return open; },
      "POST /api/v1/savings/till-sessions/s-1/transfers": (body) => ({ ...(body as object), transferId: "x-1", currency: "KES",
        status: "PENDING", amount: "50000.00" }),
      "POST /api/v1/savings/till-sessions/s-1/close": () => { state = "closing"; return { ...open, status: "CLOSING", variance: "-100.00" }; },
    });
    renderAt("/teller", session("teller-1", "TELLER"), api);
    const till = await screen.findByLabelText("Till");
    await waitFor(() => expect(within(till).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choose a till", "NBO01-T1 (NBO01)"]));
    await userEvent.selectOptions(till, "NBO01-T1");
    await userEvent.click(screen.getByRole("button", { name: "Open till" }));
    expect(await screen.findByText(/Till NBO01-T1 is open/)).toBeTruthy();
    await userEvent.type(await screen.findByLabelText("Transfer amount"), "50000");
    await userEvent.click(screen.getByRole("button", { name: "Request transfer" }));
    expect(await screen.findByText(/waiting for a supervisor/)).toBeTruthy();
    const transfer = calls.find((c) => c.path.endsWith("/transfers"));
    expect(transfer?.body).toMatchObject({ direction: "VAULT_TO_TILL", amount: "50000" });
    await userEvent.type(screen.getByLabelText("Counted cash"), "49900");
    await userEvent.click(screen.getByRole("button", { name: "Count and close till" }));
    expect(await screen.findByText(/Count differs by -100.00/)).toBeTruthy();
    expect(calls.find((c) => c.path.endsWith("/close"))?.body).toEqual({ countedAmount: "49900" });
    // While a supervisor decides, the teller can neither transfer nor count again.
    await waitFor(() => expect(screen.queryByRole("button", { name: "Count and close till" })).toBeNull());
  });

  it("asks a supervisor for a note before accepting a till difference", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/savings/teller/transactions": () => [],
      "GET /api/v1/savings/cash-transfers": () => [],
      "GET /api/v1/savings/till-sessions/closing": () => [{ sessionId: "s-9", tillCode: "NBO01-T2", teller: "user:teller-2",
        currency: "KES", status: "CLOSING", expectedCash: "79900.00", countedAmount: "79850.00", variance: "-50.00" }],
      "POST /api/v1/savings/till-sessions/s-9/approve-variance": () => ({ sessionId: "s-9", status: "CLOSED" }),
    });
    renderAt("/teller", session("sup-1", "SUPERVISOR"), api);
    const accept = await screen.findByRole("button", { name: "Accept difference" });
    expect((accept as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText("Note for till NBO01-T2"), "Shortage accepted");
    await userEvent.click(accept);
    await waitFor(() => expect(calls.find((c) => c.method === "POST")?.body).toEqual({ reason: "Shortage accepted" }));
  });

  it("keeps the day open while a till is open and closes it for operations once balanced", async () => {
    let tillOpen = true;
    const { api, calls } = fakeApi({
      "GET /api/v1/end-of-day": () => ({ businessDate: "2026-11-30", activeLoans: 0, interestPostingDay: true,
        lastClosedDate: "2026-11-29", openTills: tillOpen ? [{ tillCode: "NBO01-T1", teller: "user:teller-1", status: "OPEN" }] : [] }),
      "GET /api/v1/end-of-day/alerts": () => [],
      "GET /api/v1/end-of-day/attempts?limit=10": () => [],
      "GET /api/v1/end-of-day/schedule": () => SCHEDULE_OFF,
      "POST /api/v1/end-of-day/run": () => ({ closedDate: "2026-11-30", newBusinessDate: "2026-12-01", loansProcessed: 1,
        loanInterestAccrued: "5.00", penaltiesCharged: "0.00", loansInArrears: 1, loansClosed: 1, creditApplied: "2060.00", reconciliationDifferences: 0,
        accountsAccrued: 2,
        interestAccrued: "8.25", accountsCredited: 2, interestCredited: "21.03", taxWithheld: "3.71" }),
    });
    const view = renderAt("/end-of-day", session("ops-1", "OPERATIONS"), api);
    const close = await screen.findByRole("button", { name: "Close business day 2026-11-30" });
    expect(screen.getByText("NBO01-T1")).toBeTruthy();
    expect((close as HTMLButtonElement).disabled).toBe(true);
    view.unmount();
    tillOpen = false;
    renderAt("/end-of-day", session("ops-1", "OPERATIONS"), api);
    const ready = await screen.findByRole("button", { name: "Close business day 2026-11-30" });
    await waitFor(() => expect((ready as HTMLButtonElement).disabled).toBe(false));
    await userEvent.click(ready);
    expect((await screen.findByRole("status")).textContent).toMatch(/now 2026-12-01.*Loans: 1 processed, interest accrued 5.00.*credited: 21.03 to 2 account\(s\), tax withheld 3.71/);
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
  });

  it("shows the end-of-day state to a branch manager without the close button", async () => {
    const { api } = fakeApi({
      "GET /api/v1/end-of-day": () => ({ businessDate: "2026-11-20", activeLoans: 3, interestPostingDay: false, openTills: [] }),
      "GET /api/v1/end-of-day/alerts": () => [],
      "GET /api/v1/end-of-day/attempts?limit=10": () => [],
      "GET /api/v1/end-of-day/schedule": () => SCHEDULE_OFF,
    });
    renderAt("/end-of-day", session("manager-1", "BRANCH_MANAGER"), api);
    expect(await screen.findByText(/3 active: interest accrual, penalties and arrears/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /close business day/i })).toBeNull();
  });

  it("shows end-of-day alerts, the attempts and an interrupted run that operations can resume", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/end-of-day": () => ({ businessDate: "2026-12-02", activeLoans: 0, interestPostingDay: false, openTills: [],
        runStatus: "RUNNING", runInProgress: false }),
      "GET /api/v1/end-of-day/alerts": () => [
        { code: "EOD_INTERRUPTED", severity: "CRITICAL", message: "End of day for 2026-12-02 stopped part-way because the process running it ended." },
        { code: "EOD_LATE", severity: "WARNING", message: "2026-12-02 was due to close at 2 Dec 2026 21:00 (Africa/Nairobi) and is still open." }],
      "GET /api/v1/end-of-day/attempts?limit=10": () => [
        { attemptId: 7, businessDate: "2026-12-02", trigger: "SCHEDULED", actor: "system:eod-scheduler", startedAt: "2026-12-02T18:00:00Z", outcome: "RUNNING" },
        { attemptId: 6, businessDate: "2026-12-01", trigger: "MANUAL", actor: "user:ops-1", startedAt: "2026-12-01T20:50:00Z",
          finishedAt: "2026-12-01T20:51:00Z", outcome: "COMPLETED" },
        { attemptId: 5, businessDate: "2026-12-01", trigger: "SCHEDULED", actor: "system:eod-scheduler", startedAt: "2026-12-01T18:30:00Z",
          finishedAt: "2026-12-01T18:31:00Z", outcome: "FAILED", reason: "SCHED_TEST_INJECTED: reconciliation store unavailable" }],
      "GET /api/v1/end-of-day/schedule": () => SCHEDULE_ON,
      "POST /api/v1/end-of-day/run": () => ({ closedDate: "2026-12-02", newBusinessDate: "2026-12-03", loansProcessed: 0,
        loanInterestAccrued: "0.00", penaltiesCharged: "0.00", loansInArrears: 0, loansClosed: 0, creditApplied: "0.00",
        reconciliationDifferences: 0, accountsAccrued: 0, interestAccrued: "0.00", accountsCredited: 0, interestCredited: "0.00", taxWithheld: "0.00" }),
    });
    renderAt("/end-of-day", session("ops-1", "OPERATIONS"), api);
    const alerts = await screen.findAllByRole("alert");
    expect(alerts.map((a) => a.textContent)).toEqual([
      expect.stringMatching(/^Needs action now: End of day for 2026-12-02 stopped part-way/),
      expect.stringMatching(/^Attention: 2026-12-02 was due to close/)]);
    expect(await screen.findByText(/reconciliation store unavailable/)).toBeTruthy();
    expect(screen.getByText(/yes, daily at 21:00 \(Africa\/Nairobi\)/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Change schedule" })).toBeNull();
    const resume = await screen.findByRole("button", { name: "Resume closing business day 2026-12-02" });
    expect((resume as HTMLButtonElement).disabled).toBe(false);
    await userEvent.click(resume);
    await waitFor(() => expect(calls.filter((c) => c.method === "POST")).toHaveLength(1));
  });

  it("lets a supervisor switch on the end-of-day schedule", async () => {
    let current: Record<string, unknown> = SCHEDULE_OFF;
    const { api, calls } = fakeApi({
      "GET /api/v1/end-of-day": () => ({ businessDate: "2026-11-28", activeLoans: 0, interestPostingDay: false, openTills: [] }),
      "GET /api/v1/end-of-day/alerts": () => [],
      "GET /api/v1/end-of-day/attempts?limit=10": () => [],
      "GET /api/v1/end-of-day/schedule": () => current,
      "PUT /api/v1/end-of-day/schedule": (body) => {
        current = { ...SCHEDULE_ON, ...(body as object), updatedBy: "user:sup-1", updatedAt: "2026-11-28T09:00:00Z" };
        return current;
      },
    });
    renderAt("/end-of-day", session("sup-1", "SUPERVISOR"), api);
    expect(await screen.findByText(/no - a person closes each day/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /close business day/i })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Change schedule" }));
    await userEvent.click(screen.getByLabelText("Close the day automatically"));
    const time = screen.getByLabelText("Time of day (24h, tenant time zone)");
    await userEvent.clear(time);
    await userEvent.type(time, "22:30");
    await userEvent.click(screen.getByRole("button", { name: "Save schedule" }));
    await waitFor(() => expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ enabled: true, runAt: "22:30",
      overrunMinutes: 60, maxAttempts: 3, retryMinutes: 15, maxCatchUpDays: 3 }));
    expect(await screen.findByText(/yes, daily at 22:30/)).toBeTruthy();
    expect(screen.getByRole("status").textContent).toBe("Schedule saved.");
  });

  it("shows the prudential returns with each requirement met or not, and the loans behind a class", async () => {
    const line = (l: string, label: string, amount: string, accounts: { accountCode: string; name: string; amount: string }[] = []) =>
      ({ line: l, label, amount, accounts });
    const { api } = fakeApi({
      "GET /api/v1/regulatory/prudential": () => ({
        asOf: "2026-11-28", currency: "KES", periodClosed: false,
        settings: { regulationDefaults: false, includeCurrentYearSurplus: false },
        capital: {
          lines: [line("SHARE_CAPITAL", "Fully paid-up members' shares", "1500000.00", [{ accountCode: "3000-MEMBER-SHARES", name: "Shares", amount: "1500000.00" }])],
          coreCapital: "1900000.00", institutionalCapital: "400000.00", totalAssets: "2620855.00", totalDeposits: "365855.00",
          checks: [
            { code: "CORE_CAPITAL_MINIMUM", label: "Core capital of at least 10000000.00 (Reg 9(a))", numerator: "1900000.00", minimum: "10000000.00", met: false },
            { code: "CORE_TO_DEPOSITS", label: "Core capital to total deposits (Reg 9(d))", numerator: "1900000.00", denominator: "365855.00",
              valuePct: "519.33", minimum: "8.00", met: true }] },
        liquidity: { lines: [], liquidAssets: "2365855.00", depositsAndShortTermLiabilities: "365855.00",
          checks: [{ code: "LIQUIDITY", label: "Liquid assets to savings deposits and short-term liabilities (Reg 13)", numerator: "2365855.00",
            denominator: "365855.00", valuePct: "646.66", minimum: "15.00", met: true }] },
        classification: {
          rows: [
            { loanClass: "PERFORMING", daysPastDue: "0", loans: 6, outstandingPrincipal: "72000.00", allowancePct: "1.00", requiredAllowance: "720.00" },
            { loanClass: "WATCH", daysPastDue: "1-30", loans: 0, outstandingPrincipal: "0.00", allowancePct: "5.00", requiredAllowance: "0.00" },
            { loanClass: "SUBSTANDARD", daysPastDue: "31-180", loans: 4, outstandingPrincipal: "48000.00", allowancePct: "25.00", requiredAllowance: "12000.00" }],
          totalLoans: 10, grossLoans: "120000.00", requiredAllowance: "12720.00",
          allowanceHeld: line("LOAN_LOSS_ALLOWANCE", "Allowance held", "5000.00"), allowanceShortfall: "7720.00",
          nonPerformingLoans: "48000.00", nonPerformingRatioPct: "40.00", interestInSuspense: "0.00", interestAccruedOnNonPerforming: "1080.00",
          ledgerLoanPrincipal: "120000.00", unexplainedPrincipalDifference: "0.00" },
        unmappedBalanceSheetAccounts: [{ accountCode: "1200-LOANS-PRINCIPAL", name: "Loans", amount: "120000.00" }],
        notes: ["The accounting period containing 2026-11-28 is still open: figures can change until finance closes it.",
          "Interest of 1080.00 accrued on non-performing loans is still in income and receivables; Reg 42 requires it to be suspended. Interest suspension is not built yet."],
      }),
      "GET /api/v1/regulatory/prudential/loans?asOf=2026-11-28&loanClass=SUBSTANDARD": () => [
        { loanNumber: "LN-P0000003", branchCode: "NBO01", daysPastDue: 58, loanClass: "SUBSTANDARD", outstandingPrincipal: "12000.00", requiredAllowance: "3000.00" }],
    });
    renderAt("/regulatory", session("sup-1", "SUPERVISOR"), api);
    expect(await screen.findByText(/Provisional: the period is still open/)).toBeTruthy();
    expect(screen.getByText(/Reg 42 requires it to be suspended/)).toBeTruthy();
    const capital = screen.getByRole("heading", { name: "Capital adequacy (Regulation 9)" }).closest("section")!;
    const rows = within(capital).getAllByRole("row").map((r) => r.textContent);
    expect(rows).toContain("Core capital of at least 10000000.00 (Reg 9(a))1,900,000.0010,000,000.00Not met");
    expect(rows).toContain("Core capital to total deposits (Reg 9(d))1,900,000.00 / 365,855.00519.33%8.00%Met");
    expect(screen.getByText("7,720.00")).toBeTruthy();
    expect(screen.getByText("Interest on non-performing loans still in income").nextSibling?.textContent).toBe("1,080.00");
    expect(screen.queryByRole("link", { name: "Settings and account mapping" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Watch" })).toBeNull(); // no loans to list
    await userEvent.click(screen.getByRole("button", { name: "Substandard" }));
    expect(await screen.findByRole("link", { name: "LN-P0000003" })).toBeTruthy();
    expect(screen.getByText("3,000.00")).toBeTruthy();
  });

  it("lets finance switch on interest suspension, offering only unused asset accounts", async () => {
    let current: Record<string, unknown> = { minCoreCapital: "10000000.00", minCoreToAssetsPct: "10.00", minInstitutionalToAssetsPct: "8.00",
      minCoreToDepositsPct: "8.00", minLiquidityPct: "15.00", watchMaxDpd: 30, substandardMaxDpd: 180, doubtfulMaxDpd: 360,
      provisionPerformingPct: "1.00", provisionWatchPct: "5.00", provisionSubstandardPct: "25.00", provisionDoubtfulPct: "50.00",
      provisionLossPct: "100.00", includeCurrentYearSurplus: true, interestSuspensionEnabled: false, regulationDefaults: false,
      updatedBy: "user:fin-1" };
    const { api, calls } = fakeApi({
      "GET /api/v1/regulatory/settings": () => current,
      "GET /api/v1/regulatory/mapping": () => ({ lines: [{ line: "CASH", accountClass: "ASSET", description: "Notes and coins" }],
        accounts: [{ accountCode: "1000-VAULT", name: "Vault", accountClass: "ASSET", line: "CASH" },
          { accountCode: "1650-SUSPENSE", name: "Interest in suspense", accountClass: "ASSET" },
          { accountCode: "3000-SHARES", name: "Shares", accountClass: "EQUITY" }] }),
      "PUT /api/v1/regulatory/settings": (body) => {
        current = { ...current, ...(body as object) };
        return current;
      },
    });
    renderAt("/regulatory/setup", session("fin-1", "FINANCE"), api);
    await userEvent.click(await screen.findByRole("button", { name: "Change settings" }));
    await userEvent.click(screen.getByLabelText("Suspend interest on non-performing loans (Reg 42)"));
    const account = screen.getByLabelText(/Interest in suspense account/);
    expect(within(account).getAllByRole("option").map((o) => o.textContent)).toEqual(["(none)", "1650-SUSPENSE Interest in suspense"]);
    await userEvent.selectOptions(account, "1650-SUSPENSE");
    await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(calls.find((c) => c.method === "PUT")?.body).toMatchObject({
      interestSuspensionEnabled: true, interestSuspenseAccount: "1650-SUSPENSE", watchMaxDpd: 30 }));
    expect(await screen.findByText("yes, into 1650-SUSPENSE")).toBeTruthy();
  });

  it("lets finance map ledger accounts to return lines, offering only lines of the account's class", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/regulatory/settings": () => ({ minCoreCapital: "10000000.00", minCoreToAssetsPct: "10.00", minInstitutionalToAssetsPct: "8.00",
        minCoreToDepositsPct: "8.00", minLiquidityPct: "15.00", watchMaxDpd: 30, substandardMaxDpd: 180, doubtfulMaxDpd: 360,
        provisionPerformingPct: "1.00", provisionWatchPct: "5.00", provisionSubstandardPct: "25.00", provisionDoubtfulPct: "50.00",
        provisionLossPct: "100.00", includeCurrentYearSurplus: true, regulationDefaults: true }),
      "GET /api/v1/regulatory/mapping": () => ({
        lines: [{ line: "SHARE_CAPITAL", accountClass: "EQUITY", description: "Members' shares" },
          { line: "CASH", accountClass: "ASSET", description: "Notes and coins" },
          { line: "BANK_BALANCES", accountClass: "ASSET", description: "Bank balances" }],
        accounts: [{ accountCode: "1000-VAULT", name: "Vault", accountClass: "ASSET", line: "BANK_BALANCES" },
          { accountCode: "3000-SHARES", name: "Shares", accountClass: "EQUITY" }] }),
      "PUT /api/v1/regulatory/mapping": () => ({ lines: [], accounts: [] }),
    });
    renderAt("/regulatory/setup", session("fin-1", "FINANCE"), api);
    expect(await screen.findByText(/defaults from the Deposit-taking Sacco Business Regulations, 2010/)).toBeTruthy();
    const vault = await screen.findByLabelText("Return line for 1000-VAULT");
    expect(within(vault).getAllByRole("option").map((o) => o.textContent)).toEqual(["(not used)", "Notes and coins", "Bank balances"]);
    await userEvent.selectOptions(vault, "CASH");
    await userEvent.selectOptions(screen.getByLabelText("Return line for 3000-SHARES"), "SHARE_CAPITAL");
    await userEvent.click(screen.getByRole("button", { name: "Save mapping (2 change(s))" }));
    await waitFor(() => expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ changes: [
      { accountCode: "1000-VAULT", line: "CASH" }, { accountCode: "3000-SHARES", line: "SHARE_CAPITAL" }] }));
    expect(await screen.findByText("Account mapping saved.")).toBeTruthy();
  });

  it("quotes an early settlement and settles the loan once the member has paid enough", async () => {
    let settled = false;
    const { api, calls } = fakeApi({
      "GET /api/v1/lending/loans/LN26-0000007/schedule": () => ({ loanNumber: "LN26-0000007", status: settled ? "CLOSED" : "ACTIVE",
        installments: [{ number: 1, dueDate: "2026-12-01", principal: "1000.00", interest: "360.00", total: "1360.00", closingBalance: "11000.00" }] }),
      "GET /api/v1/lending/loans/LN26-0000007/settlement": () => settled
        ? { loanNumber: "LN26-0000007", status: "CLOSED", currency: "KES", creditHeld: "500.00", canSettle: false,
          settlement: { businessDate: "2026-11-29", payoff: "12324.00", interestWaived: "2016.00", creditLeft: "500.00", settledBy: "user:ops-1" } }
        : { loanNumber: "LN26-0000007", status: "ACTIVE", businessDate: "2026-11-29", currency: "KES", principal: "12000.00", interest: "324.00",
          penalty: "0.00", payoff: "12324.00", interestWaived: "2016.00", creditHeld: "12824.00", stillToPay: "0.00", canSettle: true },
      "GET /api/v1/lending/credit-refunds?loanNumber=LN26-0000007": () => [],
      "POST /api/v1/lending/loans/LN26-0000007/settlement": () => {
        settled = true;
        return { payoff: "12324.00", interestWaived: "2016.00", creditLeft: "500.00" };
      },
    });
    renderAt("/loans/LN26-0000007", session("ops-1", "OPERATIONS"), api);
    expect(await screen.findByRole("heading", { name: "Early settlement" })).toBeTruthy();
    expect(screen.getByText("Payoff on 2026-11-29").nextSibling?.textContent).toContain("12,324.00");
    await userEvent.click(screen.getByRole("button", { name: "Settle loan now" }));
    expect(await screen.findByText("Loan settled: 12324.00 paid off; 2016.00 of scheduled interest not charged; 500.00 of credit left.")).toBeTruthy();
    expect(calls.find((c) => c.method === "POST")?.body).toMatchObject({ idempotencyKey: expect.any(String) });
    expect(await screen.findByRole("heading", { name: "Credit on this loan" })).toBeTruthy();
    await userEvent.type(screen.getByLabelText("Member's savings account"), "SA26-0000001");
    await userEvent.type(screen.getByLabelText("Amount"), "500.00");
    await userEvent.type(screen.getByLabelText("Reason"), "Member settled early and asked for the balance");
    expect(screen.getByRole("button", { name: "Request refund to savings" })).toBeTruthy();
  });

  it("shows the settlement quote without a settle button to a loan officer", async () => {
    const { api } = fakeApi({
      "GET /api/v1/lending/loans/LN26-0000008/schedule": () => ({ loanNumber: "LN26-0000008", status: "ACTIVE", installments: [] }),
      "GET /api/v1/lending/loans/LN26-0000008/settlement": () => ({ loanNumber: "LN26-0000008", status: "ACTIVE", businessDate: "2026-11-29",
        currency: "KES", principal: "12000.00", interest: "324.00", penalty: "0.00", payoff: "12324.00", interestWaived: "2016.00",
        creditHeld: "0.00", stillToPay: "12324.00", canSettle: false }),
    });
    renderAt("/loans/LN26-0000008", session("officer-1", "LOAN_OFFICER"), api);
    expect(await screen.findByText(/pays the amount still to pay to the loan number/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Settle loan now" })).toBeNull();
  });

  it("lets a supervisor approve a refund of loan credit, but not one they asked for", async () => {
    const refund = (id: string, by: string) => ({ refundId: id, loanNumber: "LN26-0000007", amount: "500.00", currency: "KES", method: "SAVINGS",
      accountNumber: "SA26-0000001", reason: "Member settled early and asked for the balance", status: "REQUESTED", requestedBy: by,
      requestedAt: "2026-11-29T08:00:00Z" });
    const { api, calls } = fakeApi({
      "GET /api/v1/lending/credit-refunds?status=REQUESTED": () => [refund("r-1", "user:ops-1"), refund("r-2", "user:sup-1")],
      "GET /api/v1/lending/credit-refunds?status=APPROVED": () => [],
      "POST /api/v1/lending/credit-refunds/r-1/approve": () => ({ ...refund("r-1", "user:ops-1"), status: "APPROVED", decidedBy: "user:sup-1" }),
    });
    renderAt("/credit-refunds", session("sup-1", "SUPERVISOR"), api);
    const buttons = await screen.findAllByRole("button", { name: "Approve refund" });
    expect(buttons).toHaveLength(1); // the supervisor's own request cannot be decided by them
    await userEvent.click(buttons[0]!);
    expect(await screen.findByText("500.00 paid into SA26-0000001 from loan LN26-0000007.")).toBeTruthy();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/v1/lending/credit-refunds/r-1/approve"]);
  });

  it("lets a teller ask for a USSD PIN setup for an active member, never handling the PIN", async () => {
    const customer = {
      customerNumber: "CU26-0000002", firstName: "Wanjiku", lastName: "Kamau", status: "ACTIVE", createdBy: "user:officer-1",
      idType: "NATIONAL_ID", idNumber: "23456789", phoneNumber: "254722000002",
    };
    const setup = { setupId: "s-1", customerNumber: "CU26-0000002", customerName: "Wanjiku Kamau", kind: "FIRST",
      reason: "ID sighted, member present", status: "REQUESTED", requestedBy: "user:teller-1", requestedAt: "2026-11-29T08:00:00Z" };
    let requested = false;
    const { api, calls } = fakeApi({
      "GET /api/v1/customers/CU26-0000002": () => customer,
      "GET /api/v1/savings/accounts?customerNumber=CU26-0000002": () => [],
      ...noTill,
      "GET /api/v1/customers/CU26-0000002/member-pin": () => ({ customerNumber: "CU26-0000002", status: "NONE", failedAttempts: 0,
        maxAttempts: 3, setups: requested ? [setup] : [], recentEvents: [], pendingSetup: requested ? setup : undefined }),
      "POST /api/v1/customers/CU26-0000002/member-pin/setups": () => { requested = true; return setup; },
    });
    renderAt("/customers/CU26-0000002", session("teller-1", "TELLER"), api);
    expect(await screen.findByText("Not set up")).toBeTruthy();
    const ask = screen.getByRole("button", { name: "Request PIN setup" });
    expect((ask as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(/how the member was identified/i), "ID sighted, member present");
    await userEvent.click(ask);
    expect(await screen.findByText(/a branch manager or supervisor must approve it/)).toBeTruthy();
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.body).toMatchObject({ reason: "ID sighted, member present" });
    expect(Object.keys(post.body as object).sort()).toEqual(["idempotencyKey", "reason"]);
    // The teller who asked cannot approve it.
    expect(await screen.findByText(/requested by user:teller-1/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Approve PIN setup" })).toBeNull();
    expect(screen.queryByLabelText(/pin$/i)).toBeNull();
  });

  it("shows a locked PIN and the member's USSD activity, and lets a manager approve someone else's reset", async () => {
    const customer = {
      customerNumber: "CU26-0000003", firstName: "Otieno", lastName: "Ouma", status: "ACTIVE", createdBy: "user:officer-1",
      idType: "NATIONAL_ID", idNumber: "34567890", phoneNumber: "254733000003",
    };
    const reset = { setupId: "s-2", customerNumber: "CU26-0000003", customerName: "Otieno Ouma", kind: "RESET",
      reason: "Member forgot PIN, ID sighted", status: "REQUESTED", requestedBy: "user:teller-1", requestedAt: "2026-11-29T08:00:00Z" };
    const { api, calls } = fakeApi({
      "GET /api/v1/customers/CU26-0000003": () => customer,
      "GET /api/v1/savings/accounts?customerNumber=CU26-0000003": () => [],
      "GET /api/v1/customers/CU26-0000003/member-pin": () => ({ customerNumber: "CU26-0000003", status: "LOCKED", failedAttempts: 3,
        maxAttempts: 3, lockedAt: "2026-11-28T19:00:00Z", pinSetAt: "2026-11-20T10:00:00Z", pendingSetup: reset, setups: [reset],
        recentEvents: [{ occurredAt: "2026-11-28T19:00:00Z", event: "PIN_LOCKED" }, { occurredAt: "2026-11-28T18:59:00Z", event: "PIN_WRONG" },
          { occurredAt: "2026-11-27T12:00:00Z", event: "PAYMENT_PROMPT", detail: "LN26-0000009 500.00" }] }),
      "POST /api/v1/member-pin-setups/s-2/approve": () => ({ ...reset, status: "APPROVED", decidedBy: "user:manager-1" }),
    });
    renderAt("/customers/CU26-0000003", session("manager-1", "BRANCH_MANAGER"), api);
    expect(await screen.findByText("Locked after too many wrong attempts")).toBeTruthy();
    expect(screen.getByText("3 of 3")).toBeTruthy();
    expect(screen.getByText("M-Pesa payment prompt")).toBeTruthy();
    expect(screen.getByText("LN26-0000009 500.00")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "Approve PIN setup" }));
    expect(await screen.findByText(/The member can now dial the USSD code/)).toBeTruthy();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/v1/member-pin-setups/s-2/approve"]);
  });

  it("shows a member's SMS and lets a teller record their language and opt-outs", async () => {
    const customer = {
      customerNumber: "CU26-0000005", firstName: "Chebet", lastName: "Kirui", status: "ACTIVE", createdBy: "user:officer-1",
      idType: "NATIONAL_ID", idNumber: "45678901", phoneNumber: "254755000005",
    };
    const msg = (id: string, template: string, status: string, extra: object = {}) => ({ messageId: id, createdAt: "2026-11-28T08:00:00Z",
      customerNumber: "CU26-0000005", template, category: "TRANSACTIONAL", language: "en", body: `Sacco: body of ${id}`, status, attempts: 1,
      ...extra });
    const { api, calls } = fakeApi({
      "GET /api/v1/customers/CU26-0000005": () => customer,
      "GET /api/v1/savings/accounts?customerNumber=CU26-0000005": () => [],
      "GET /api/v1/customers/CU26-0000005/member-pin": () => ({ customerNumber: "CU26-0000005", status: "NONE", failedAttempts: 0,
        maxAttempts: 3, setups: [], recentEvents: [] }),
      "GET /api/v1/customers/CU26-0000005/sms": () => ({ customerNumber: "CU26-0000005", phone: "2547*****005", smsEnabled: true,
        preferences: { language: "en", paymentAlerts: true, reminders: true },
        messages: [msg("m-1", "PAYMENT_SAVINGS", "DELIVERED", { deliveredAt: "2026-11-28T08:01:00Z" }),
          msg("m-2", "LOAN_DUE_SOON", "QUEUED", { category: "REMINDER", lastError: "HTTP 503" }),
          msg("m-3", "PAYMENT_LOAN", "SUPPRESSED", { attempts: 0, suppressedReason: "MEMBER_OPTED_OUT" })] }),
      "PUT /api/v1/customers/CU26-0000005/sms-preferences": () => ({ customerNumber: "CU26-0000005", smsEnabled: true,
        preferences: { language: "sw", paymentAlerts: true, reminders: false, updatedBy: "user:teller-1" }, messages: [] }),
    });
    renderAt("/customers/CU26-0000005", session("teller-1", "TELLER"), api);
    expect(await screen.findByText("Savings deposit received")).toBeTruthy();
    expect(screen.getByText("Delivered")).toBeTruthy();
    expect(screen.getByText("Retrying (1 tried)")).toBeTruthy();
    expect(screen.getByText("HTTP 503")).toBeTruthy();
    expect(screen.getByText("Not sent: member opted out")).toBeTruthy();
    expect(screen.getByText(/registered phone 2547\*\*\*\*\*005/)).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText("Language"), "sw");
    await userEvent.click(screen.getByLabelText("Loan reminders"));
    await userEvent.click(screen.getByRole("button", { name: "Save SMS choices" }));
    expect(await screen.findByText("SMS choices saved.")).toBeTruthy();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ language: "sw", paymentAlerts: true, reminders: false });
  });

  it("lets a supervisor switch SMS on and shows failed messages; operations only read the settings", async () => {
    const settings = { enabled: false, signature: "Test Sacco", senderId: "", ussdCode: "*483*1#", reminderHour: 8 };
    const overview = (status: string) => ({ lastSevenDays: { QUEUED: 2, DISPATCHED: 0, SENT: 1, DELIVERED: 40, FAILED: 1, SUPPRESSED: 3 },
      messages: status === "FAILED" ? [{ messageId: "f-1", createdAt: "2026-11-28T08:00:00Z", customerNumber: "CU26-0000007",
        template: "PAYMENT_LOAN", category: "TRANSACTIONAL", language: "sw", body: "Sacco: Tumepokea KES 500.00", status: "FAILED",
        attempts: 5, lastError: "HTTP 503: busy" }] : [] });
    const { api, calls } = fakeApi({
      "GET /api/v1/notifications/settings": () => settings,
      "PUT /api/v1/notifications/settings": () => ({ ...settings, enabled: true, updatedBy: "user:sup-1" }),
      "GET /api/v1/notifications/sms?status=FAILED": () => overview("FAILED"),
      "GET /api/v1/notifications/sms?status=QUEUED": () => overview("QUEUED"),
    });
    renderAt("/sms", session("sup-1", "SUPERVISOR"), api);
    expect(await screen.findByText("Loan payment received (Swahili)")).toBeTruthy();
    expect(screen.getByText("HTTP 503: busy")).toBeTruthy();
    expect(screen.getByText("40")).toBeTruthy();
    await userEvent.click(screen.getByLabelText("Send SMS to members"));
    await userEvent.click(screen.getByRole("button", { name: "Save SMS settings" }));
    expect(await screen.findByText("SMS to members is on.")).toBeTruthy();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ enabled: true, signature: "Test Sacco", senderId: "",
      ussdCode: "*483*1#", reminderHour: 8 });
    await userEvent.selectOptions(screen.getByLabelText("Show"), "QUEUED");
    expect(await screen.findByText("None.")).toBeTruthy();

    const ops = fakeApi({
      "GET /api/v1/notifications/settings": () => settings,
      "GET /api/v1/notifications/sms?status=FAILED": () => overview("FAILED"),
    });
    cleanup();
    renderAt("/sms", session("ops-1", "OPERATIONS"), ops.api);
    expect(await screen.findByText("Only a supervisor changes these settings.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save SMS settings" })).toBeNull();
  });

  it("lists PIN requests for a supervisor, who cannot approve their own", async () => {
    const setup = (id: string, by: string, name: string) => ({ setupId: id, customerNumber: `CU26-000000${id.slice(-1)}`, customerName: name,
      kind: "FIRST", reason: "ID sighted, member present", status: "REQUESTED", requestedBy: by, requestedAt: "2026-11-29T08:00:00Z" });
    const { api, calls } = fakeApi({
      "GET /api/v1/member-pin-setups": () => [setup("s-4", "user:teller-1", "Akinyi Odhiambo"), setup("s-5", "user:sup-1", "Mutua Kioko")],
      "POST /api/v1/member-pin-setups/s-4/reject": () => ({ ...setup("s-4", "user:teller-1", "Akinyi Odhiambo"), status: "REJECTED" }),
    });
    renderAt("/pin-requests", session("sup-1", "SUPERVISOR"), api);
    expect(await screen.findAllByRole("button", { name: "Approve PIN setup" })).toHaveLength(1);
    const reject = screen.getByRole("button", { name: "Reject" });
    expect((reject as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText("Note for PIN setup of CU26-0000004"), "Phone number not confirmed");
    await userEvent.click(reject);
    expect(await screen.findByText("PIN setup for Akinyi Odhiambo rejected.")).toBeTruthy();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({ reason: "Phone number not confirmed" });
  });

  it("shows portfolio at risk by branch, loans in arrears and closed loans holding credit", async () => {
    const { api, calls } = fakeApi({
      "GET /api/v1/reports/portfolio-at-risk": () => ({ asOf: "2026-11-30",
        branches: [{ branchCode: "NBO01", currency: "KES", loans: 1, portfolio: "10000.00", atRisk1: "10000.00", atRisk30: "0.00",
          atRisk90: "0.00", par1: "100.00", par30: "0.00", par90: "0.00" }],
        loansInArrears: [{ loanNumber: "LN-B00001", branchCode: "NBO01", daysPastDue: 6, bucket: "DPD_1_30", classification: "WATCH",
          arrearsAmount: "5300.00", outstandingPrincipal: "10000.00" }],
        closedWithCredit: [{ loanNumber: "LN-B00002", branchCode: "NBO01", closedOn: "2026-11-29", creditBalance: "40.00" }] }),
      "GET /api/v1/reports/portfolio-at-risk?asOf=2026-11-29": () => ({ asOf: "2026-11-29", branches: [], loansInArrears: [], closedWithCredit: [] }),
    });
    renderAt("/portfolio", session("manager-1", "BRANCH_MANAGER"), api);
    expect(await screen.findByText("By branch, as of 2026-11-30")).toBeTruthy();
    expect(screen.getByText("100.00%")).toBeTruthy();
    expect(screen.getByText("LN-B00001")).toBeTruthy();
    expect(screen.getByText("LN-B00002")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("As of"), { target: { value: "2026-11-29" } });
    await userEvent.click(screen.getByRole("button", { name: "Show" }));
    expect(await screen.findByText("No loan is in arrears.")).toBeTruthy();
    expect(calls.some((c) => c.path.endsWith("asOf=2026-11-29"))).toBe(true);
  });

  it("lets a supervisor approve another person's write-off but not their own", async () => {
    const base = { currency: "KES", status: "PENDING", daysPastDue: 185, principalOutstanding: "6000.00",
      interestOutstanding: "240.00", penaltyOutstanding: "0.00", reason: "Customer unreachable since May", recovered: "0.00" };
    const { api, calls } = fakeApi({
      "GET /api/v1/lending/write-offs?status=PENDING": () => [
        { ...base, writeOffId: "w-1", loanNumber: "LN-X00001", requestedBy: "user:manager-1" },
        { ...base, writeOffId: "w-2", loanNumber: "LN-X00002", requestedBy: "user:sup-1" }],
      "GET /api/v1/lending/write-offs?status=APPROVED": () => [],
      "POST /api/v1/lending/write-offs/w-1/approve": () => ({ ...base, writeOffId: "w-1", loanNumber: "LN-X00001",
        status: "APPROVED", writtenOff: "6240.00" }),
    });
    renderAt("/write-offs", session("sup-1", "SUPERVISOR"), api);
    expect(await screen.findAllByRole("button", { name: "Approve write-off" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Request write-off" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Approve write-off" }));
    expect((await screen.findByRole("status")).textContent).toContain("LN-X00001 written off: 6240.00 KES");
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/v1/lending/write-offs/w-1/approve"]);
  });

  it("shows a balanced trial balance and drills into an account and an entry", async () => {
    const { api } = fakeApi({
      "GET /api/v1/accounting/trial-balance": () => ({ asOf: "2026-12-01",
        accounts: [{ accountCode: "2960-WHT-PAYABLE", name: "Withholding tax payable", accountClass: "LIABILITY", currency: "KES",
          debit: "0.00", credit: "3.71", balance: "3.71" },
          { accountCode: "5300-SAVINGS-INTEREST", name: "Interest on savings", accountClass: "EXPENSE", currency: "KES",
          debit: "3.71", credit: "0.00", balance: "3.71" }],
        totals: [{ currency: "KES", debit: "3.71", credit: "3.71", difference: "0.00", balanced: true }] }),
      "GET /api/v1/accounting/gl-accounts/2960-WHT-PAYABLE/activity?from=2026-12-01&to=2026-12-01": () => ({
        account: { accountCode: "2960-WHT-PAYABLE", name: "Withholding tax payable", accountClass: "LIABILITY" },
        from: "2026-12-01", to: "2026-12-01", openingBalance: "0.00", totalDebit: "0.00", totalCredit: "3.71", closingBalance: "3.71",
        lines: [{ businessDate: "2026-11-30", entryId: "e-1", source: "EOD", reference: "savings-interest:x:2026-11-30",
          branchCode: "NBO01", credit: "3.71", balance: "3.71" }], truncated: false }),
      "GET /api/v1/accounting/journal-entries/e-1": () => ({ entryId: "e-1", businessDate: "2026-11-30", source: "EOD",
        reference: "savings-interest:x:2026-11-30", actor: "user:ops-b", lines: [
          { lineNo: 1, accountCode: "2120-INTEREST-PAYABLE", accountName: "Payable", side: "DEBIT", amount: "24.00", branchCode: "NBO01" },
          { lineNo: 2, accountCode: "2110-SAVINGS-CONTROL", accountName: "Savings", side: "CREDIT", amount: "20.40", branchCode: "NBO01", depositAccount: "2000000014" },
          { lineNo: 3, accountCode: "2960-WHT-PAYABLE", accountName: "WHT", side: "CREDIT", amount: "3.60", branchCode: "NBO01" }] }),
    });
    renderAt("/ledger", session("fin-1", "FINANCE"), api);
    expect((await screen.findByRole("status")).textContent).toContain("KES: debits 3.71, credits 3.71 - balanced.");
    await userEvent.click(screen.getByRole("link", { name: "2960-WHT-PAYABLE" }));
    expect(await screen.findByText("savings-interest:x:2026-11-30")).toBeTruthy();
    await userEvent.click(screen.getByRole("link", { name: "savings-interest:x:2026-11-30" }));
    expect(await screen.findByText("2000000014")).toBeTruthy();
    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getAllByRole("link").map((a) => a.textContent)).toEqual(["Worklist", "General ledger", "Prudential returns"]);
  });

  it("flags control accounts that do not match their records", async () => {
    const { api } = fakeApi({
      "GET /api/v1/accounting/reconciliation": () => ({ recorded: false, checkedAt: "2026-12-01T09:00:00+03:00", differences: 1,
        results: [
          { checkCode: "LOAN_PRINCIPAL", accountCode: "1200-LOANS-PRINCIPAL", currency: "KES", ledgerBalance: "500.00",
            subledgerBalance: "0.00", difference: "500.00", items: 2, reconciled: false },
          { checkCode: "SAVINGS_CONTROL", accountCode: "2110-SAVINGS-CONTROL", currency: "KES", ledgerBalance: "41754.96",
            subledgerBalance: "41754.96", difference: "0.00", items: 3, reconciled: true }] }),
    });
    renderAt("/ledger/reconciliation", session("fin-1", "FINANCE"), api);
    expect((await screen.findByRole("status")).textContent).toContain("1 control account(s) do not match");
    expect(screen.getByText("Loan principal")).toBeTruthy();
    expect(screen.getAllByText("500.00")).toHaveLength(2);
  });

  it("proposes a balanced manual journal and keeps the proposer from posting it", async () => {
    const journal = { journalId: "j-1", businessDate: "2026-12-01", narrative: "Reclassify a fee booked to clearing", status: "PENDING",
      requestedBy: "user:fin-1", totalDebit: "250.00", lines: [
        { lineNo: 1, accountCode: "1120-MPESA-C2B-CLEARING", side: "DEBIT", amount: "250.00", branchCode: "NBO01" },
        { lineNo: 2, accountCode: "4200-FEE-WITHDRAWAL", side: "CREDIT", amount: "250.00", branchCode: "NBO01" }] };
    const { api, calls } = fakeApi({
      "GET /api/v1/accounting/manual-journals?status=PENDING": () => [journal],
      "GET /api/v1/accounting/manual-journals?status=POSTED": () => [],
      "POST /api/v1/accounting/manual-journals": () => journal,
    });
    renderAt("/ledger/journals", session("fin-1", "FINANCE"), api);
    expect(await screen.findByText("Your own request - a colleague must decide.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Post journal" })).toBeNull();
    await userEvent.type(screen.getByLabelText("Narrative"), "Reclassify a fee booked to clearing");
    await userEvent.type(screen.getByLabelText("Account 1"), "1120-MPESA-C2B-CLEARING");
    await userEvent.type(screen.getByLabelText("Amount 1"), "250");
    await userEvent.type(screen.getByLabelText("Branch 1"), "NBO01");
    await userEvent.type(screen.getByLabelText("Account 2"), "4200-FEE-WITHDRAWAL");
    await userEvent.type(screen.getByLabelText("Amount 2"), "200");
    await userEvent.type(screen.getByLabelText("Branch 2"), "NBO01");
    const propose = screen.getByRole("button", { name: "Propose journal" });
    expect((propose as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/out by 50.00/)).toBeTruthy();
    await userEvent.clear(screen.getByLabelText("Amount 2"));
    await userEvent.type(screen.getByLabelText("Amount 2"), "250");
    await userEvent.click(propose);
    expect((await screen.findByRole("status")).textContent).toContain("Journal for 250.00 proposed");
    expect(calls.find((c) => c.method === "POST")?.body).toMatchObject({ narrative: "Reclassify a fee booked to clearing",
      lines: [{ accountCode: "1120-MPESA-C2B-CLEARING", side: "DEBIT", amount: "250" }, { accountCode: "4200-FEE-WITHDRAWAL", side: "CREDIT", amount: "250" }] });
  });

  it("shows the income statement and a balance sheet that balances, with CSV downloads", async () => {
    const { api } = fakeApi({
      "GET /api/v1/accounting/income-statement": () => ({ from: "2026-11-01", to: "2026-11-30",
        income: [{ accountCode: "4000-INTEREST-INCOME", name: "Interest income", accountClass: "INCOME", currency: "KES", amount: "390.00" }],
        expenses: [{ accountCode: "5300-SAVINGS-INTEREST", name: "Interest on savings", accountClass: "EXPENSE", currency: "KES", amount: "24.74" }],
        totals: [{ currency: "KES", totalIncome: "390.00", totalExpenses: "24.74", netProfit: "365.26" }] }),
      "GET /api/v1/accounting/balance-sheet": () => ({ asOf: "2026-12-01",
        assets: [{ accountCode: "1200-LOANS-PRINCIPAL", name: "Loans", accountClass: "ASSET", currency: "KES", amount: "1000.00" }],
        liabilities: [{ accountCode: "2110-SAVINGS-CONTROL", name: "Savings", accountClass: "LIABILITY", currency: "KES", amount: "634.74" }],
        equity: [], totals: [{ currency: "KES", totalAssets: "1000.00", totalLiabilities: "634.74", totalEquity: "0.00",
          unclosedEarnings: "365.26", liabilitiesAndEquity: "1000.00", balanced: true }] }),
    });
    renderAt("/ledger/statements", session("fin-1", "FINANCE"), api);
    const statuses = await screen.findAllByRole("status");
    expect(statuses.map((s) => s.textContent).join(" ")).toMatch(/net profit 365.26.*balances\./s);
    expect(screen.getAllByRole("button", { name: "Download CSV" })).toHaveLength(2);
  });

  it("shows the audit trail with refused actions highlighted and the chain status", async () => {
    const { api } = fakeApi({
      "GET /api/v1/audit/staff-actions": () => [
        { chainNo: 2, occurredAt: "2026-11-20T09:02:00+03:00", actor: "user:teller-1", roles: "TELLER", method: "POST",
          route: "/api/v1/lending/write-offs/{writeOffId}/approve", path: "/api/v1/lending/write-offs/x/approve", status: 403,
          durationMs: 3, requestId: "r-2" },
        { chainNo: 1, occurredAt: "2026-11-20T09:01:00+03:00", actor: "user:officer-1", roles: "LOAN_OFFICER", method: "GET",
          route: "/api/v1/customers", path: "/api/v1/customers", query: "q=***", status: 200, durationMs: 5, requestId: "r-1" }],
      "GET /api/v1/audit/staff-actions/verify": () => ({ intact: false, firstBrokenChainNo: 2, rows: 2 }),
    });
    renderAt("/audit", session("sup-1", "SUPERVISOR"), api);
    expect((await screen.findByRole("status")).textContent).toContain("Trail broken at record 2");
    expect(await screen.findByText("/api/v1/customers?q=***")).toBeTruthy();
    expect(screen.getByText("403").closest("tr")?.className).toBe("attention");
  });
});
