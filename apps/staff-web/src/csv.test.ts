import { describe, expect, it } from "vitest";
import { csvCell, toCsv } from "./csv";

describe("csv", () => {
  it("quotes separators and quotes", () => {
    expect(toCsv([["2110-SAVINGS", "Savings, ordinary", 'He said "hi"', "41754.96"]]))
      .toBe('2110-SAVINGS,"Savings, ordinary","He said ""hi""",41754.96\r\n');
  });

  it("neutralises text a spreadsheet would run as a formula, but keeps numbers numeric", () => {
    expect(csvCell("=HYPERLINK(\"http://x\")")).toBe("\"'=HYPERLINK(\"\"http://x\"\")\"");
    expect(csvCell("+254700000000")).toBe("'+254700000000");
    expect(csvCell("@SUM(A1)")).toBe("'@SUM(A1)");
    expect(csvCell("-100.00")).toBe("-100.00");
    expect(csvCell(-5)).toBe("-5");
    expect(csvCell(null)).toBe("");
  });
});
