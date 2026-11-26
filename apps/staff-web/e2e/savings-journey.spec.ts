import { expect, test } from "@playwright/test";
import { signIn, verifiedCustomer } from "./helpers";

/**
 * Savings through the real stack: teller opens their till -> verified customer -> teller opens an
 * account -> cash deposit -> large withdrawal waits for a supervisor (the teller cannot approve it)
 * -> approved -> the statement shows both movements -> vault cash in (approved) -> end-of-day count
 * with a 100 shortage, accepted by the supervisor.
 */
test("a teller day: till, deposit, approved withdrawal, vault transfer and closing count", async ({ browser }) => {
  const officer = await signIn(browser, "officer");
  const manager = await signIn(browser, "manager");
  const customer = await verifiedCustomer(officer, manager, "Wanjiru");

  const teller = await signIn(browser, "teller");
  await teller.goto("/teller");
  await teller.getByLabel("Till", { exact: true }).selectOption("NBO01-T1");
  await teller.getByRole("button", { name: "Open till" }).click();
  await expect(teller.getByRole("status")).toContainText("Till NBO01-T1 is open");

  await teller.goto(`/customers/${customer}`);
  await teller.getByRole("button", { name: "Open savings account" }).click();
  const heading = teller.getByRole("heading", { level: 1, name: /^Account 2\d{9}$/ });
  await expect(heading).toBeVisible();
  const account = (await heading.textContent())!.replace("Account ", "").trim();

  await teller.getByLabel("Transaction").selectOption("DEPOSIT");
  await teller.getByLabel(/Amount/).fill("100000");
  await teller.getByRole("button", { name: "Post" }).click();
  await expect(teller.getByRole("status")).toContainText("deposited");

  await teller.getByLabel("Transaction").selectOption("WITHDRAWAL");
  await teller.getByLabel(/Amount/).fill("60000");
  await teller.getByRole("button", { name: "Post" }).click();
  await expect(teller.getByRole("status")).toContainText(/do not pay out/i);
  await teller.goto("/teller");
  await expect(teller.getByRole("row", { name: new RegExp(account) }).getByRole("button", { name: "Approve" })).toHaveCount(0);

  const supervisor = await signIn(browser, "supervisor");
  await supervisor.goto("/teller");
  await supervisor.getByRole("row", { name: new RegExp(account) }).getByRole("button", { name: "Approve" }).click();
  await expect(supervisor.getByRole("row", { name: new RegExp(account) })).toHaveCount(0);

  await teller.goto(`/accounts/${account}`);
  await expect(teller.getByText("KES 40,000.00").first()).toBeVisible();
  const rows = teller.locator("table.compact tbody tr");
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText("KES 100,000.00");
  await expect(rows.nth(1)).toContainText("KES 60,000.00");
  await expect(rows.nth(1)).toContainText("KES 40,000.00");

  // Share capital (ADR-0040): finance sets it up; the teller buys 5,000 in shares from the account;
  // an officer asks to transfer 2,000 to another member and a supervisor approves it.
  const finance = await signIn(browser, "finance");
  await finance.goto("/dividends");
  await finance.getByLabel("Value of one share").fill("100");
  await finance.getByLabel("Minimum holding").fill("1000");
  await finance.getByLabel("Share capital account (equity)").fill("3100-SHARE-CAPITAL");
  await finance.getByLabel("Dividends paid from (equity)").fill("3300-RETAINED-EARNINGS");
  await finance.getByLabel("Dividends payable (liability)").fill("2410-DIVIDENDS-PAYABLE");
  await finance.getByLabel("Withholding tax payable (liability)").fill("2420-WHT-DIVIDENDS");
  await finance.getByRole("button", { name: "Save share settings" }).click();
  await expect(finance.getByText(/Last changed by user:/)).toBeVisible();
  await teller.goto("/shares");
  await teller.getByLabel("Member number").fill(customer);
  await teller.getByRole("button", { name: "Show shares" }).click();
  await expect(teller.getByText("No share movements yet.")).toBeVisible();
  await teller.getByLabel("Pay from the member's savings account").fill(account);
  await teller.getByLabel("Amount to buy (whole shares)").fill("5000");
  await teller.getByRole("button", { name: "Buy shares" }).click();
  await expect(teller.getByRole("status")).toContainText(`${customer} now holds 5,000.00 in shares.`);
  await teller.goto(`/accounts/${account}`);
  await expect(teller.getByText("KES 35,000.00").first()).toBeVisible();
  const other = await verifiedCustomer(officer, manager, "Kamau");
  await officer.goto("/shares");
  await officer.getByLabel("Member number").fill(customer);
  await officer.getByRole("button", { name: "Show shares" }).click();
  await officer.getByLabel("To member number").fill(other);
  await officer.getByLabel("Amount to transfer (whole shares)").fill("2000");
  await officer.getByLabel(/^Reason/).fill("Gift to a family member (e2e)");
  await officer.getByRole("button", { name: "Ask for the transfer" }).click();
  await expect(officer.getByRole("status")).toContainText("it needs approval");
  await expect(officer.getByRole("row", { name: new RegExp(other) }).getByRole("button", { name: "Approve" })).toHaveCount(0);
  await supervisor.goto("/shares");
  const shareTransfer = supervisor.getByRole("row", { name: /Gift to a family member \(e2e\)/ });
  await shareTransfer.getByRole("button", { name: "Approve" }).click();
  await expect(shareTransfer).toContainText("APPROVED");
  await supervisor.reload();
  await expect(supervisor.getByRole("row", { name: new RegExp(`^${other}.*2,000\\.00`) })).toBeVisible();
  await expect(supervisor.getByRole("row", { name: new RegExp(`^${customer}.*NBO01.*3,000\\.00`) })).toBeVisible();

  // Fixed deposits (ADR-0041): finance creates a product; the teller places 10,000 from the member's
  // savings; an officer asks to withdraw it early and the supervisor approves, paying it back today.
  await finance.goto("/fixed-deposits");
  await finance.getByLabel("Product code").fill("FD-E2E-3M");
  await finance.getByLabel("Name").fill("Three months fixed (e2e)");
  await finance.getByLabel("Term (months)").fill("3");
  await finance.getByLabel("Annual rate (%)").fill("10");
  await finance.getByLabel("Rate if withdrawn early (%)").fill("5");
  await finance.getByLabel("Deposits account (liability)").fill("2600-FIXED-DEPOSITS");
  await finance.getByLabel("Interest payable (liability)").fill("2610-FD-INTEREST-PAYABLE");
  await finance.getByLabel("Interest expense").fill("5600-FD-INTEREST");
  await finance.getByLabel("Withholding tax payable (liability)").fill("2620-WHT-FD-INTEREST");
  await finance.getByRole("button", { name: "Save product" }).click();
  await expect(finance.getByRole("row", { name: /FD-E2E-3M/ })).toBeVisible();
  await teller.goto("/fixed-deposits");
  await teller.getByLabel("Member number").fill(customer);
  await teller.getByRole("button", { name: "Show deposits" }).click();
  await teller.getByLabel("Pay from the member's savings account").fill(account);
  await teller.getByLabel("Amount to deposit").fill("10000");
  await teller.getByRole("button", { name: "Place deposit" }).click();
  await expect(teller.getByRole("status")).toContainText(/FD\d{8} placed: 10,000\.00/);
  const fixed = (await teller.getByRole("heading", { name: /^Fixed deposit FD\d{8}$/ }).textContent())!.replace("Fixed deposit ", "");
  await teller.goto(`/accounts/${account}`);
  await expect(teller.getByText("KES 25,000.00").first()).toBeVisible();
  await officer.goto("/fixed-deposits");
  await officer.getByLabel("Member number").fill(customer);
  await officer.getByRole("button", { name: "Show deposits" }).click();
  await officer.getByRole("button", { name: fixed }).click();
  await officer.getByLabel(/^Reason/).fill("Member needs the money back (e2e)");
  await officer.getByRole("button", { name: "Ask to withdraw early" }).click();
  await expect(officer.getByRole("status")).toContainText("must approve it");
  await supervisor.goto("/fixed-deposits");
  const early = supervisor.getByRole("row", { name: new RegExp(fixed) });
  await early.getByRole("button", { name: "Approve and pay" }).click();
  await expect(early).toContainText("APPROVED");
  await teller.goto(`/accounts/${account}`);
  await expect(teller.getByText("KES 35,000.00").first()).toBeVisible();

  // Member exit (ADR-0042): finance sets the notice period (none on this TEST-ONLY stack); the member
  // with shares is told to transfer them first; a new member with nothing outstanding asks to leave,
  // and the supervisor approves.
  await finance.goto("/member-exits");
  await finance.getByLabel("Notice period (days)").fill("0");
  await finance.getByLabel("Member refunds payable (liability)").fill("2450-MEMBER-REFUNDS");
  await finance.getByRole("button", { name: "Save exit settings" }).click();
  await expect(finance.getByText(/Last changed by user:/)).toBeVisible();
  const leaver = await verifiedCustomer(officer, manager, "Wafula");
  await officer.goto("/member-exits");
  await officer.getByLabel("Member number").fill(customer);
  await officer.getByRole("button", { name: "Show position" }).click();
  await expect(officer.getByRole("alert")).toContainText("in shares to transfer to another member");
  await officer.getByLabel("Member number").fill(leaver);
  await officer.getByRole("button", { name: "Show position" }).click();
  await expect(officer.getByText("Nothing outstanding.")).toBeVisible();
  await officer.getByLabel(/^Reason the member gives/).fill("Relocating upcountry (e2e)");
  await officer.getByRole("button", { name: "Ask to leave" }).click();
  await expect(officer.getByRole("status")).toContainText(`${leaver} asked to leave`);
  await supervisor.goto("/member-exits");
  const exitRow = supervisor.getByRole("row", { name: new RegExp(leaver) });
  await exitRow.getByRole("button", { name: "Approve exit" }).click();
  await expect(exitRow).toContainText("APPROVED");

  // Vault cash into the till, approved by the supervisor.
  await teller.goto("/teller");
  await teller.getByLabel("Transfer amount").fill("20000");
  await teller.getByRole("button", { name: "Request transfer" }).click();
  await expect(teller.getByRole("status")).toContainText("waiting for a supervisor");
  await supervisor.goto("/teller");
  const transfer = supervisor.getByRole("row", { name: /NBO01-T1.*Vault to till/ });
  await transfer.getByRole("button", { name: "Approve" }).click();
  await expect(transfer).toHaveCount(0);

  // 100,000 in, 60,000 out, 20,000 from the vault: 60,000 expected. The teller counts 59,900.
  await teller.goto("/teller");
  await expect(teller.getByText("KES 60,000.00")).toBeVisible();
  await teller.getByLabel("Counted cash").fill("59900");
  await teller.getByRole("button", { name: "Count and close till" }).click();
  await expect(teller.getByRole("status")).toContainText("Count differs by -100.00");

  await supervisor.goto("/teller");
  const count = supervisor.getByRole("row", { name: /NBO01-T1/ });
  await count.getByLabel("Note for till NBO01-T1").fill("Shortage of 100 accepted; teller notified");
  await count.getByRole("button", { name: "Accept difference" }).click();
  await expect(count).toHaveCount(0);
  await teller.goto("/teller");
  await expect(teller.getByRole("button", { name: "Open till" })).toBeVisible();

  // The day's state is visible to the supervisor (closing it needs loan end of day, not built yet).
  await supervisor.goto("/end-of-day");
  await expect(supervisor.getByRole("heading", { level: 1, name: "End of day" })).toBeVisible();
  await expect(supervisor.getByText("All tills are closed and balanced.")).toBeVisible();
  // The supervisor sets the end-of-day time (left switched off: the two journeys share one tenant).
  await expect(supervisor.getByText("no - a person closes each day")).toBeVisible();
  await supervisor.getByRole("button", { name: "Change schedule" }).click();
  await supervisor.getByLabel("Time of day (24h, tenant time zone)").fill("22:30");
  await supervisor.getByLabel("Minutes between attempts").fill("20");
  await supervisor.getByRole("button", { name: "Save schedule" }).click();
  await expect(supervisor.getByRole("status").filter({ hasText: "Schedule saved." })).toBeVisible();
  await expect(supervisor.getByText("3 attempt(s), 20 minutes apart")).toBeVisible();
  await expect(supervisor.getByText(/^user:\S+, /)).toBeVisible();
  await expect(supervisor.getByRole("alert")).toHaveCount(0);
  await supervisor.goto("/portfolio");
  await expect(supervisor.getByRole("heading", { level: 1, name: "Portfolio at risk" })).toBeVisible();
  await supervisor.goto("/write-offs");
  await expect(supervisor.getByRole("heading", { level: 1, name: "Loan write-offs" })).toBeVisible();

  // Finance sees the ledger the day's work produced, and it balances.
  await finance.goto("/ledger");
  await expect(finance.getByRole("status").first()).toContainText("balanced.");
  await expect(finance.getByRole("link", { name: "1001-TILL-NBO01-01" })).toBeVisible();
  await finance.getByRole("link", { name: "Reconciliation of control accounts" }).click();
  await expect(finance.getByRole("heading", { level: 1, name: "Reconciliation" })).toBeVisible();
  // Everything the journeys posted through the real services matches its business records.
  await expect(finance.getByRole("status")).toContainText("Every control account matches its records.");
  await finance.goto("/ledger/periods");
  await expect(finance.getByRole("heading", { level: 1, name: "Accounting periods" })).toBeVisible();
  await finance.goto("/ledger/journals");
  await expect(finance.getByRole("button", { name: "Propose journal" })).toBeVisible();
  await finance.goto("/ledger/statements");
  await expect(finance.getByText(/ - balances\./)).toBeVisible();
  // Prudential returns need a closed business day (none on this stack); finance maps an account for them.
  await finance.goto("/regulatory");
  await expect(finance.getByRole("heading", { level: 1, name: "Prudential returns" })).toBeVisible();
  await expect(finance.getByText(/no business day has been closed yet/)).toBeVisible();
  await finance.getByRole("link", { name: "Settings and account mapping" }).click();
  await expect(finance.getByText(/defaults from the Deposit-taking Sacco Business Regulations, 2010/)).toBeVisible();
  await finance.getByLabel("Return line for 1001-TILL-NBO01-01").selectOption("CASH");
  await finance.getByRole("button", { name: "Save mapping (1 change(s))" }).click();
  await expect(finance.getByRole("status").filter({ hasText: "Account mapping saved." })).toBeVisible();
  await expect(finance.getByLabel("Return line for 1001-TILL-NBO01-01")).toHaveValue("CASH");
  await finance.goto("/ledger");
  const [download] = await Promise.all([
    finance.waitForEvent("download"),
    finance.getByRole("button", { name: "Download CSV" }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/^trial-balance-\d{4}-\d{2}-\d{2}\.csv$/);
  const csv = await new Promise<string>((resolve, reject) => {
    download.createReadStream().then((stream) => {
      let text = "";
      stream.on("data", (chunk: Buffer) => { text += chunk.toString("utf8"); });
      stream.on("end", () => resolve(text));
      stream.on("error", reject);
    }, reject);
  });
  expect(csv.replace(/^\uFEFF/, "").split("\r\n")[0]).toBe("Account,Name,Class,Currency,Debit,Credit");
  expect(csv).toContain("1001-TILL-NBO01-01");

  // The supervisor's audit trail shows the day's staff actions and verifies.
  await supervisor.goto("/audit");
  await expect(supervisor.getByRole("status")).toContainText("Trail intact");
  await expect(supervisor.getByText("/api/v1/savings/teller/transactions", { exact: false }).first()).toBeVisible();
});
