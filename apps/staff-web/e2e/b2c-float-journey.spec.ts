import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

/**
 * Finance asks M-Pesa for the B2C float of short code 600000. The integration runtime sends the
 * Account Balance request to the TEST-ONLY simulator, which answers on the callback; the page then
 * shows the Utility Account balance next to the ledger clearing account (ADR-0039).
 */
test("finance checks the B2C float through the integration runtime and the simulator", async ({ browser }) => {
  const finance = await signIn(browser, "finance");
  await finance.goto("/b2c-float");
  await expect(finance.getByRole("heading", { name: "B2C 600000" })).toBeVisible();
  const checkNow = finance.getByRole("button", { name: "Check balance now" });
  // a daily check may already be waiting for M-Pesa; the button comes back once it is answered
  await expect(async () => {
    await finance.reload();
    await expect(checkNow).toBeEnabled({ timeout: 1000 });
  }).toPass({ timeout: 60_000 });
  await checkNow.click();
  await expect(finance.getByRole("status")).toContainText("Balance check sent to M-Pesa");
  await expect(async () => {
    await finance.reload();
    await expect(finance.getByRole("row").filter({ hasText: "On demand" }).filter({ hasText: /Available \d/ }).first())
      .toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 60_000 });
  await expect(finance.getByRole("cell", { name: "Utility Account" })).toBeVisible();
  await expect(finance.getByText("Clearing account 1110-MPESA-B2C-CLEARING (ledger)")).toBeVisible();
});
