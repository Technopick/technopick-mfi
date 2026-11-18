import { expect, test, type Browser, type Page } from "@playwright/test";

/**
 * A loan's life through the real stack, as four different staff members, each in their own
 * browser session: officer applies -> manager approves (loan + schedule) -> officer requests the
 * M-Pesa B2C disbursement -> supervisor approves and sends -> integration -> TEST-ONLY Daraja
 * simulator -> result callback -> core posts and activates the loan, which the manager sees ACTIVE.
 */
const USERS = {
  officer: "e2e-only-officer",
  manager: "e2e-only-manager",
  supervisor: "e2e-only-supervisor",
} as const;

async function signIn(browser: Browser, user: keyof typeof USERS): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(USERS[user]);
  await page.locator("#kc-login").click();
  await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
  return page;
}

test("apply, approve, disburse by M-Pesa and activate a loan", async ({ browser }) => {
  const customer = `E2E-${Date.now()}`;

  const officer = await signIn(browser, "officer");
  await officer.goto("/applications/new");
  await officer.getByLabel("Customer reference").fill(customer);
  await officer.getByLabel("Product code").fill("BIASHARA");
  await officer.getByLabel("Branch code").fill("NBO01");
  await officer.getByLabel("Amount (KES)").fill("50000");
  await officer.getByLabel("Term (months)").fill("12");
  await officer.getByLabel("Purpose").fill("Restock the shop (e2e)");
  await officer.getByRole("button", { name: "Submit for approval" }).click();
  await expect(officer.getByRole("heading", { name: `Application - ${customer}` })).toBeVisible();
  await expect(officer.getByText("KES 49,000.00").first()).toBeVisible(); // 50,000 less the 2% fee
  // Four eyes: the officer has no approve action.
  await expect(officer.getByRole("button", { name: /approve/i })).toHaveCount(0);
  const applicationUrl = officer.url();

  const manager = await signIn(browser, "manager");
  await manager.goto(applicationUrl);
  await manager.getByRole("button", { name: "Approve and create loan" }).click();
  const loanLink = manager.getByRole("link", { name: /^LN\d{2}-\d{7}$/ });
  await expect(loanLink).toBeVisible();
  const loanNumber = (await loanLink.textContent())!.trim();

  await officer.goto("/disbursements");
  await officer.getByLabel("Loan number").fill(loanNumber);
  await officer.getByLabel("Customer M-Pesa number").fill("0712345678");
  await officer.getByRole("button", { name: "Request disbursement" }).click();
  await expect(officer.getByRole("status")).toContainText("KES 49,000.00");

  const supervisor = await signIn(browser, "supervisor");
  await supervisor.goto("/disbursements");
  supervisor.on("dialog", (d) => void d.accept());
  const row = supervisor.getByRole("row", { name: new RegExp(loanNumber) });
  await row.getByRole("button", { name: "Approve and send" }).click();
  await expect(supervisor.getByRole("row", { name: new RegExp(loanNumber) })).toHaveCount(0);

  // The payment travels through RabbitMQ, the simulator and its callback; poll until the loan is active.
  await manager.goto(`/loans/${loanNumber}`);
  await expect(async () => {
    await manager.reload();
    await expect(manager.getByText("ACTIVE", { exact: true })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 90_000, intervals: [2_000] });
  await expect(manager.locator("table tbody tr")).toHaveCount(12); // the 12-month schedule
});
