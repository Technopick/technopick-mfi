import { expect, test, type Page } from "@playwright/test";
import { signIn, verifiedMember } from "./helpers";

/** The TEST-ONLY USSD handset simulator (infrastructure/e2e), posting to the integration runtime like an aggregator. */
const HANDSET = process.env.E2E_USSD_URL ?? "http://localhost:18091/";

/** Dials, then answers each prompt in turn; returns the last screen. */
async function dial(handset: Page, ...keys: string[]): Promise<string> {
  await handset.getByRole("button", { name: /^Dial/ }).click();
  for (const key of keys) {
    await expect(handset.getByRole("button", { name: "Send" })).toBeEnabled();
    await handset.getByLabel("Reply").fill(key);
    await handset.getByRole("button", { name: "Send" }).click();
  }
  await expect(handset.getByRole("button", { name: /^Dial/ })).toBeEnabled();
  return (await handset.getByRole("status").textContent()) ?? "";
}

/**
 * Member self-service through the real stack: a verified member with their own phone and a
 * savings account -> a teller asks for a USSD PIN setup, a supervisor approves -> the member, on
 * the TEST-ONLY handset, confirms their ID number and chooses a PIN -> signs in and deposits 250
 * by M-Pesa prompt -> the TEST-ONLY Daraja simulator pays -> the account shows 250 to staff and on
 * USSD -> the member's USSD activity is on their customer page.
 */
test("a member sets a USSD PIN and deposits to savings by M-Pesa prompt", async ({ browser }) => {
  const officer = await signIn(browser, "officer");
  const manager = await signIn(browser, "manager");
  const local = "071" + String(Date.now()).slice(-6) + "4";
  const msisdn = "+254" + local.slice(1);
  const { customer, idNumber } = await verifiedMember(officer, manager, "Nekesa", local);

  const teller = await signIn(browser, "teller");
  await teller.goto(`/customers/${customer}`);
  await teller.getByRole("button", { name: "Open savings account" }).click();
  const heading = teller.getByRole("heading", { level: 1, name: /^Account 2\d{9}$/ });
  await expect(heading).toBeVisible();
  const account = (await heading.textContent())!.replace("Account ", "").trim();

  // A teller asks for the PIN setup; a supervisor approves it.
  await teller.goto(`/customers/${customer}`);
  await expect(teller.getByText("Not set up")).toBeVisible();
  await teller.getByLabel(/how the member was identified/i).fill("National ID sighted, member at the counter (e2e)");
  await teller.getByRole("button", { name: "Request PIN setup" }).click();
  await expect(teller.getByRole("status")).toContainText("must approve it");
  const supervisor = await signIn(browser, "supervisor");
  await supervisor.goto("/pin-requests");
  await supervisor.getByRole("row", { name: new RegExp(customer) }).getByRole("button", { name: "Approve PIN setup" }).click();
  await expect(supervisor.getByRole("status")).toContainText("Approved for Nekesa");

  // The member chooses a PIN on the handset.
  const handset = await (await browser.newContext()).newPage();
  await handset.goto(HANDSET);
  await expect(handset.getByText(/TEST-ONLY USSD simulator/)).toBeVisible();
  await handset.getByLabel("Phone number").fill(msisdn);
  expect(await dial(handset, "11111111")).toContain("ID number does not match");
  expect(await dial(handset, idNumber, "4826", "4826")).toContain("Your PIN is set.");

  // Deposit 250 by M-Pesa prompt; the TEST-ONLY Daraja simulator pays it.
  expect(await dial(handset, "4826", "5", "250", "1")).toContain("Enter your M-Pesa PIN on the prompt");
  await expect(async () => {
    await teller.goto(`/accounts/${account}`);
    await expect(teller.getByText("KES 250.00").first()).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 60_000 });
  expect(await dial(handset, "4826", "1")).toContain("250.00");

  // Staff see the member's USSD activity; the PIN is never shown.
  await teller.goto(`/customers/${customer}`);
  await expect(teller.getByText("Active", { exact: true })).toBeVisible();
  await expect(teller.getByText("M-Pesa payment prompt")).toBeVisible();
  await expect(teller.getByText("Wrong ID number during setup")).toBeVisible();
  await expect(teller.getByText("4826")).toHaveCount(0);
});
