/**
 * CSV export done in the browser from data the page already shows (ADR-0026).
 * Text cells that a spreadsheet would treat as a formula (starting with = + - @ tab or CR) are
 * prefixed with an apostrophe; numbers are written as plain decimals so they stay numeric.
 */
export type Cell = string | number | null | undefined;

const NUMBER = /^-?\d+(\.\d+)?$/;

export function csvCell(value: Cell): string {
  if (value === null || value === undefined) return "";
  let s = String(value);
  if (typeof value !== "number" && !NUMBER.test(s) && /^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function toCsv(rows: Cell[][]): string {
  return rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Offers the rows as a file download (UTF-8 with BOM so Excel reads names correctly). */
export function downloadCsv(filename: string, rows: Cell[][]): void {
  const blob = new Blob(["﻿" + toCsv(rows)], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
