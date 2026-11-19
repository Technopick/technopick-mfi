import { expect, type Browser, type Page } from "@playwright/test";

/** TEST-ONLY realm users (infrastructure/e2e/keycloak). */
export const USERS = {
  officer: "e2e-only-officer",
  manager: "e2e-only-manager",
  supervisor: "e2e-only-supervisor",
  teller: "e2e-only-teller",
} as const;

export async function signIn(browser: Browser, user: keyof typeof USERS): Promise<Page> {
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

/** Officer registers a customer with consent; manager verifies. Returns the customer number. */
export async function verifiedCustomer(officer: Page, manager: Page, firstName = "Achieng"): Promise<string> {
  const idNumber = String(Date.now()).slice(-8);
  await officer.goto("/customers/new");
  await officer.getByLabel("First name").fill(firstName);
  await officer.getByLabel("Last name").fill("Otieno");
  await officer.getByLabel("Date of birth").fill("1990-05-01");
  await officer.getByLabel("ID number").fill(idNumber);
  await officer.getByLabel("M-Pesa number").fill("0712345678");
  await officer.getByLabel("Branch code").fill("NBO01");
  await officer.getByRole("checkbox").check();
  await officer.getByRole("button", { name: /^Register/ }).click();
  const heading = officer.getByRole("heading", { level: 2, name: /^CU\d{2}-\d{7}$/ });
  await expect(heading).toBeVisible();
  const customer = (await heading.textContent())!.trim();
  await manager.goto(`/customers/${customer}`);
  await manager.getByLabel(/what was checked/i).fill("National ID sighted at branch (e2e)");
  await manager.getByRole("button", { name: "Verify identity" }).click();
  await expect(manager.getByText("ACTIVE", { exact: true }).first()).toBeVisible();
  return customer;
}
