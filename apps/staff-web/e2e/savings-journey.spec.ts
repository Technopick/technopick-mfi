import { expect, test } from "@playwright/test";
import { signIn, verifiedCustomer } from "./helpers";

/**
 * Savings through the real stack: verified customer -> teller opens an account -> cash deposit ->
 * large withdrawal waits for a supervisor (the teller cannot approve it) -> approved -> the
 * statement shows both movements with running balances.
 */
test("open a savings account, deposit, and withdraw above the limit with approval", async ({ browser }) => {
  const officer = await signIn(browser, "officer");
  const manager = await signIn(browser, "manager");
  const customer = await verifiedCustomer(officer, manager, "Wanjiru");

  const teller = await signIn(browser, "teller");
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
});
