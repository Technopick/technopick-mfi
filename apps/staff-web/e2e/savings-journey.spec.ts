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
  await supervisor.goto("/portfolio");
  await expect(supervisor.getByRole("heading", { level: 1, name: "Portfolio at risk" })).toBeVisible();
  await supervisor.goto("/write-offs");
  await expect(supervisor.getByRole("heading", { level: 1, name: "Loan write-offs" })).toBeVisible();

  // Finance sees the ledger the day's work produced, and it balances.
  const finance = await signIn(browser, "finance");
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
});
