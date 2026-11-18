/**
 * Money is handled as decimal strings end to end (the API never sends floats); these helpers
 * only format and validate text, they never convert amounts to JavaScript numbers.
 */
const AMOUNT = /^(\d+)(?:\.(\d{1,2}))?$/;

export function isAmount(text: string): boolean {
  return AMOUNT.test(text.trim());
}

/** "1234567.5" -> "1,234,567.50"; anything that is not a plain decimal is returned unchanged. */
export function formatAmount(text: string | null | undefined): string {
  if (text == null) return "";
  const m = AMOUNT.exec(text.trim());
  if (!m) return text;
  const whole = (m[1] ?? "0").replace(/^0+(?=\d)/, "").replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const cents = (m[2] ?? "").padEnd(2, "0");
  return `${whole}.${cents}`;
}

export function formatMoney(amount: string | null | undefined, currency = "KES"): string {
  return amount == null ? "" : `${currency} ${formatAmount(amount)}`;
}
