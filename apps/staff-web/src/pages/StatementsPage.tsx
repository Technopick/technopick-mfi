import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { Schemas } from "../api/client";
import { downloadCsv, type Cell } from "../csv";
import { Empty, Loading, ProblemBox, Section } from "../components/ui";
import { useLoad } from "../hooks";

type IncomeStatement = Schemas["IncomeStatement"];
type BalanceSheet = Schemas["BalanceSheet"];
type Line = Schemas["FinancialStatementLine"];
const BASE = "/api/v1/accounting";
const fmt = (v?: string | null) => (v == null ? "" : Number(v).toLocaleString("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }));

function Lines({ title, lines }: { title: string; lines?: Line[] }) {
  return (
    <>
      <tr><th colSpan={3}>{title}</th></tr>
      {!lines?.length ? <tr><td colSpan={3} className="muted">None</td></tr> : lines.map((l) => (
        <tr key={l.accountCode}>
          <td><Link to={`/ledger/accounts/${encodeURIComponent(l.accountCode ?? "")}`}>{l.accountCode}</Link></td>
          <td>{l.name}</td><td className="num">{fmt(l.amount)}</td>
        </tr>
      ))}
    </>
  );
}

const rows = (section: string, lines?: Line[]): Cell[][] =>
  (lines ?? []).map((l) => [section, l.accountCode, l.name, l.currency, l.amount]);

/** Income statement for a date range and balance sheet at a date, each downloadable as CSV. */
export function StatementsPage() {
  const [params, setParams] = useSearchParams();
  const [from, setFrom] = useState(params.get("from") ?? "");
  const [to, setTo] = useState(params.get("to") ?? "");
  const [branch, setBranch] = useState(params.get("branchCode") ?? "");
  const isQuery = new URLSearchParams();
  if (params.get("from")) isQuery.set("from", params.get("from")!);
  if (params.get("to")) isQuery.set("to", params.get("to")!);
  if (params.get("branchCode")) isQuery.set("branchCode", params.get("branchCode")!);
  const bsQuery = new URLSearchParams();
  if (params.get("to")) bsQuery.set("asOf", params.get("to")!);
  if (params.get("branchCode")) bsQuery.set("branchCode", params.get("branchCode")!);
  const is = useLoad<IncomeStatement>(`${BASE}/income-statement${isQuery.size ? `?${isQuery}` : ""}`);
  const bs = useLoad<BalanceSheet>(`${BASE}/balance-sheet${bsQuery.size ? `?${bsQuery}` : ""}`);
  const i = is.data;
  const b = bs.data;
  const suffix = branch.trim() ? `-${branch.trim()}` : "";

  return (
    <>
      <p><Link to="/ledger">Trial balance</Link></p>
      <h1>Financial statements</h1>
      <form className="search" onSubmit={(e) => {
        e.preventDefault();
        const next = new URLSearchParams();
        if (from) next.set("from", from);
        if (to) next.set("to", to);
        if (branch.trim()) next.set("branchCode", branch.trim());
        setParams(next);
      }}>
        <label>From<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label>To<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <label>Branch<input value={branch} placeholder="all" onChange={(e) => setBranch(e.target.value)} /></label>
        <button type="submit">Show</button>
      </form>
      <ProblemBox problem={is.error ?? bs.error} />
      {is.loading && !i ? <Loading /> : i && (
        <Section title={`Income statement, ${i.from} to ${i.to}${i.branchCode ? `, branch ${i.branchCode}` : ""}`}>
          <p><button type="button" className="secondary" onClick={() => downloadCsv(`income-statement-${i.from}-to-${i.to}${suffix}.csv`, [
            ["Section", "Account", "Name", "Currency", "Amount"], ...rows("Income", i.income), ...rows("Expenses", i.expenses),
            ...(i.totals ?? []).map((t): Cell[] => ["Net profit", "", "", t.currency, t.netProfit])])}>Download CSV</button></p>
          <table>
            <tbody>
              <Lines title="Income" lines={i.income} />
              <Lines title="Expenses" lines={i.expenses} />
            </tbody>
          </table>
          {!i.totals?.length ? <Empty>No income or expenses in this period.</Empty> : i.totals.map((t) => (
            <p key={t.currency} role="status" className="ok">{t.currency}: income {fmt(t.totalIncome)}, expenses {fmt(t.totalExpenses)},
              {" "}net {Number(t.netProfit) < 0 ? "loss" : "profit"} {fmt(String(Math.abs(Number(t.netProfit))))}.</p>
          ))}
        </Section>
      )}
      {bs.loading && !b ? <Loading /> : b && (
        <Section title={`Balance sheet at ${b.asOf}${b.branchCode ? `, branch ${b.branchCode}` : ""}`}>
          <p><button type="button" className="secondary" onClick={() => downloadCsv(`balance-sheet-${b.asOf}${suffix}.csv`, [
            ["Section", "Account", "Name", "Currency", "Amount"], ...rows("Assets", b.assets), ...rows("Liabilities", b.liabilities),
            ...rows("Equity", b.equity),
            ...(b.totals ?? []).map((t): Cell[] => ["Earnings not yet closed to equity", "", "", t.currency, t.unclosedEarnings])])}>
            Download CSV</button></p>
          <table>
            <tbody>
              <Lines title="Assets" lines={b.assets} />
              <Lines title="Liabilities" lines={b.liabilities} />
              <Lines title="Equity" lines={b.equity} />
            </tbody>
          </table>
          {(b.totals ?? []).map((t) => (
            <p key={t.currency} role="status" className={t.balanced ? "ok" : "problem"}>
              {t.currency}: assets {fmt(t.totalAssets)}; liabilities {fmt(t.totalLiabilities)} + equity {fmt(t.totalEquity)}
              {" "}+ earnings not yet closed to equity {fmt(t.unclosedEarnings)} = {fmt(t.liabilitiesAndEquity)}
              {t.balanced ? " - balances." : " - does not balance."}
            </p>
          ))}
          {b.branchCode && <p className="muted small">A single branch's balance sheet can be out when entries span branches.</p>}
        </Section>
      )}
    </>
  );
}
