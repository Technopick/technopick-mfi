import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

/**
 * Finance uploads today's paybill statement (TEST-ONLY lines) for the seeded paybill 600991. Neither
 * line is on the ledger, so both are exceptions; finance explains them, and the supervisor's sign-off
 * is refused by the database because the day is still open (ADR-0038).
 */
test("finance reconciles an M-Pesa statement and the supervisor cannot sign off an open day", async ({ browser }) => {
  const today = new Date().toISOString().slice(0, 10); // the stack's business date is the database's current_date (UTC)
  const csv = [
    "Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance,Reason Type",
    `E2ESTMT001,${today} 10:00:00,Pay Bill from 254700000001 - TEST ONLY Acc. NOTHING,Completed,250.00,,250.00,Pay Bill`,
    `E2ESTMT001,${today} 10:00:00,Pay Bill Charge,Completed,,-5.00,245.00,Pay Bill Charge`,
  ].join("\n");

  const finance = await signIn(browser, "finance");
  await finance.goto("/mpesa-statements");
  await expect(finance.getByRole("heading", { level: 1, name: "M-Pesa statement reconciliation" })).toBeVisible();
  await expect(finance.getByLabel("M-Pesa account")).toContainText("Paybill 600991");
  await finance.getByLabel("Statement date").fill(today);
  await finance.getByLabel("Statement file (CSV)").setInputFiles({ name: `paybill-${today}.csv`, mimeType: "text/csv",
    buffer: Buffer.from(csv, "utf8") });
  await finance.getByRole("button", { name: "Upload and match" }).click();
  await expect(finance.getByRole("heading", { name: `Paybill 600991 — ${today}` })).toBeVisible();
  await expect(finance.getByText("0 matched · 2 open exceptions")).toBeVisible();
  await expect(finance.getByText("Pay Bill from 2547****001 Acc. NOTHING")).toBeVisible();
  await expect(finance.getByText(/You uploaded this statement/)).toBeVisible();

  await finance.getByLabel("Select line 1").check();
  await finance.getByLabel("Select line 2").check();
  await finance.getByLabel("Note (at least 10 characters)").fill("TEST ONLY lines, not real receipts");
  await finance.getByRole("button", { name: "Explain" }).click();
  await expect(finance.getByRole("status").filter({ hasText: "2 line(s) resolved." })).toBeVisible();
  await expect(finance.getByText("No open exceptions.")).toBeVisible();

  const supervisor = await signIn(browser, "supervisor");
  await supervisor.goto("/mpesa-statements");
  await supervisor.getByRole("row", { name: new RegExp(today) }).getByRole("button", { name: "Open" }).click();
  await supervisor.getByLabel(/Sign-off note/).fill("Reviewed in the browser journey");
  await supervisor.getByRole("button", { name: `Sign off ${today}` }).click();
  await expect(supervisor.getByRole("alert")).toContainText("This date is still the business date");
  await expect(supervisor.getByRole("alert")).toContainText("RECON_DAY_OPEN");
});
