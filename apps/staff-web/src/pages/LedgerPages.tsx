import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import type { Schemas } from "../api/client";
import { Empty, Loading, ProblemBox, Section, when } from "../components/ui";
import { useLoad } from "../hooks";

type TrialBalance = Schemas["TrialBalance"];
type Activity = Schemas["AccountActivity"];
type Entry = Schemas["EntryView"];
const BASE = "/api/v1/accounting";

const amount = (v?: string | null) => (v == null ? "" : Number(v).toLocaleString("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }));

/** Trial balance as of a business date, optionally for one branch. */
export function TrialBalancePage() {
  const [params, setParams] = useSearchParams();
  const [asOf, setAsOf] = useState(params.get("asOf") ?? "");
  const [branch, setBranch] = useState(params.get("branchCode") ?? "");
  const query = params.toString();
  const tb = useLoad<TrialBalance>(`${BASE}/trial-balance${query ? `?${query}` : ""}`);
  const t = tb.data;

  return (
    <>
      <h1>General ledger</h1>
      <p><Link to="/ledger/reconciliation">Reconciliation of control accounts</Link></p>
      <form className="search" onSubmit={(e) => {
        e.preventDefault();
        const next = new URLSearchParams();
        if (asOf) next.set("asOf", asOf);
        if (branch.trim()) next.set("branchCode", branch.trim());
        setParams(next);
      }}>
        <label>As of<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
        <label>Branch<input value={branch} placeholder="all" onChange={(e) => setBranch(e.target.value)} /></label>
        <button type="submit">Show</button>
      </form>
      <ProblemBox problem={tb.error} />
      {tb.loading && !t ? <Loading /> : t && (
        <Section title={`Trial balance as of ${t.asOf}${t.branchCode ? `, branch ${t.branchCode}` : ""}`}>
          {(t.totals ?? []).map((c) => (
            <p key={c.currency} className={c.balanced ? "ok" : "problem"} role="status">
              {c.currency}: debits {amount(c.debit)}, credits {amount(c.credit)}
              {c.balanced ? " - balanced." : ` - out by ${amount(c.difference)}${t.branchCode ? " (entries can span branches)" : ""}.`}
            </p>
          ))}
          {!t.accounts?.length ? <Empty>No balances on that date.</Empty> : (
            <table>
              <thead><tr><th>Account</th><th>Name</th><th>Class</th><th className="num">Debit</th><th className="num">Credit</th></tr></thead>
              <tbody>
                {t.accounts.map((a) => (
                  <tr key={a.accountCode}>
                    <td><Link to={`/ledger/accounts/${encodeURIComponent(a.accountCode ?? "")}?to=${t.asOf}`}>{a.accountCode}</Link></td>
                    <td>{a.name}</td><td>{a.accountClass}</td>
                    <td className="num">{a.debit === "0.00" ? "" : amount(a.debit)}</td>
                    <td className="num">{a.credit === "0.00" ? "" : amount(a.credit)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}
    </>
  );
}

function monthStart(date: string) {
  return `${date.slice(0, 8)}01`;
}

/** One GL account's movements between two dates, with opening and running balances. */
export function AccountActivityPage() {
  const { code = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const to = params.get("to") ?? new Date().toISOString().slice(0, 10);
  const from = params.get("from") ?? monthStart(to);
  const [f, setF] = useState(from);
  const [tDate, setT] = useState(to);
  const activity = useLoad<Activity>(`${BASE}/gl-accounts/${encodeURIComponent(code)}/activity?from=${from}&to=${to}`);
  const a = activity.data;

  return (
    <>
      <p><Link to="/ledger">Trial balance</Link></p>
      <h1>{code}{a?.account?.name ? ` - ${a.account.name}` : ""}</h1>
      <form className="search" onSubmit={(e) => { e.preventDefault(); setParams({ from: f, to: tDate }); }}>
        <label>From<input type="date" value={f} onChange={(e) => setF(e.target.value)} /></label>
        <label>To<input type="date" value={tDate} onChange={(e) => setT(e.target.value)} /></label>
        <button type="submit">Show</button>
      </form>
      <ProblemBox problem={activity.error} />
      {activity.loading && !a ? <Loading /> : a && (
        <Section title={`${a.account?.accountClass} account, ${a.from} to ${a.to}`}>
          <dl className="facts">
            <dt>Opening balance</dt><dd>{amount(a.openingBalance)}</dd>
            <dt>Debits</dt><dd>{amount(a.totalDebit)}</dd>
            <dt>Credits</dt><dd>{amount(a.totalCredit)}</dd>
            <dt>Closing balance</dt><dd>{amount(a.closingBalance)}</dd>
          </dl>
          {a.truncated && <p className="problem">Only the first 2,000 lines are shown; choose a shorter period.</p>}
          {!a.lines?.length ? <Empty>No movements in this period.</Empty> : (
            <table className="compact">
              <thead><tr><th>Date</th><th>Source</th><th>Reference</th><th>Narrative</th><th>Branch</th>
                <th className="num">Debit</th><th className="num">Credit</th><th className="num">Balance</th></tr></thead>
              <tbody>
                {a.lines.map((l, i) => (
                  <tr key={`${l.entryId}-${i}`}>
                    <td>{l.businessDate}</td><td>{l.source}</td>
                    <td><Link to={`/ledger/entries/${l.entryId}`}>{l.reference}</Link></td>
                    <td>{l.narrative ?? (l.depositAccount ? `Account ${l.depositAccount}` : "")}</td><td>{l.branchCode}</td>
                    <td className="num">{amount(l.debit)}</td><td className="num">{amount(l.credit)}</td>
                    <td className="num">{amount(l.balance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}
    </>
  );
}

/** One journal entry with all its lines. */
export function JournalEntryPage() {
  const { id = "" } = useParams();
  const entry = useLoad<Entry>(`${BASE}/journal-entries/${id}`);
  const e = entry.data;
  return (
    <>
      <p><Link to="/ledger">Trial balance</Link></p>
      <h1>Journal entry</h1>
      <ProblemBox problem={entry.error} />
      {entry.loading && !e ? <Loading /> : e && (
        <Section title={e.reference ?? ""}>
          <dl className="facts">
            <dt>Business date</dt><dd>{e.businessDate}</dd>
            <dt>Posted</dt><dd>{when(e.postedAt)} by {e.actor}</dd>
            <dt>Source</dt><dd>{e.source}</dd>
            {e.reversesEntryId && <><dt>Reverses</dt><dd><Link to={`/ledger/entries/${e.reversesEntryId}`}>{e.reversesEntryId}</Link></dd></>}
            {e.reversedByEntryId && <><dt>Reversed by</dt><dd><Link to={`/ledger/entries/${e.reversedByEntryId}`}>{e.reversedByEntryId}</Link></dd></>}
          </dl>
          <table>
            <thead><tr><th>#</th><th>Account</th><th>Deposit account</th><th>Branch</th><th>Narrative</th>
              <th className="num">Debit</th><th className="num">Credit</th></tr></thead>
            <tbody>
              {(e.lines ?? []).map((l) => (
                <tr key={l.lineNo}>
                  <td>{l.lineNo}</td>
                  <td><Link to={`/ledger/accounts/${encodeURIComponent(l.accountCode ?? "")}?to=${e.businessDate}`}>{l.accountCode}</Link> {l.accountName}</td>
                  <td>{l.depositAccount ?? ""}</td><td>{l.branchCode}</td><td>{l.narrative ?? ""}</td>
                  <td className="num">{l.side === "DEBIT" ? amount(l.amount) : ""}</td>
                  <td className="num">{l.side === "CREDIT" ? amount(l.amount) : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}
    </>
  );
}
