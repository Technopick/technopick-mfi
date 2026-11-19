import { screen, waitFor, within } from "@testing-library/react";
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
    });
    renderAt("/accounts/2000000014", session("teller-1", "TELLER"), api);
    await userEvent.selectOptions(await screen.findByLabelText("Transaction"), "WITHDRAWAL");
    await userEvent.type(screen.getByLabelText(/amount \(KES\)/i), "60000");
    await userEvent.click(screen.getByRole("button", { name: "Post" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/do not pay out/i);
    const post = calls.find((c) => c.method === "POST");
    expect((post?.body as Record<string, unknown>).amount).toBe("60000");
  });

  it("hides approval of a cash transaction from the teller who captured it", async () => {
    const { api } = fakeApi({
      "GET /api/v1/savings/teller/transactions": () => [{ txnId: "t-1", accountNumber: "2000000014", kind: "WITHDRAWAL",
        amount: "60000.00", currency: "KES", narrative: "Cash withdrawal", status: "PENDING_APPROVAL",
        requestedBy: "user:sup-teller" }],
    });
    renderAt("/teller", session("sup-teller", "TELLER", "SUPERVISOR"), api);
    expect(await screen.findByText(/a colleague must decide/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });
});
