import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { isAmount } from "../money";

type Journal = Schemas["ManualJournalView"];
type Period = Schemas["PeriodView"];
type Side = "DEBIT" | "CREDIT";
interface Draft { accountCode: string; side: Side; amount: string; branchCode: string; narrative: string }
const BASE = "/api/v1/accounting";
const blank = (side: Side): Draft => ({ accountCode: "", side, amount: "", branchCode: "", narrative: "" });

function totals(lines: Draft[]) {
  const sum = (s: Side) => lines.filter((l) => l.side === s && isAmount(l.amount))
    .reduce((t, l) => t + Math.round(Number(l.amount) * 100), 0);
  return { debit: sum("DEBIT"), credit: sum("CREDIT") };
}
const cents = (c: number) => (c / 100).toFixed(2);

/** Manual journals: propose (finance), post or reject (finance or supervisor, never the proposer). */
export function ManualJournalsPage() {
  const api = useApi();
  const { session } = useAuth();
  const me = session ? actorOf(session) : "";
  const canPropose = hasAnyRole(session, "FINANCE");
  const canDecide = hasAnyRole(session, "FINANCE", "SUPERVISOR");
  const pending = useLoad<Journal[]>(`${BASE}/manual-journals?status=PENDING`);
  const recent = useLoad<Journal[]>(`${BASE}/manual-journals?status=POSTED`);
  const action = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [date, setDate] = useState("");
  const [narrative, setNarrative] = useState("");
  const [lines, setLines] = useState<Draft[]>([blank("DEBIT"), blank("CREDIT")]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const t = totals(lines);
  const ready = narrative.trim().length >= 10 && lines.length >= 2 && t.debit > 0 && t.debit === t.credit
    && lines.every((l) => l.accountCode.trim() && l.branchCode.trim() && isAmount(l.amount));
  const set = (i: number, patch: Partial<Draft>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  async function propose(e: FormEvent) {
    e.preventDefault();
    const j = await action.run(() => api.post<Journal>(`${BASE}/manual-journals`, {
      idempotencyKey: key, businessDate: date || undefined, narrative: narrative.trim(),
      lines: lines.map((l) => ({ accountCode: l.accountCode.trim(), side: l.side, amount: l.amount, branchCode: l.branchCode.trim(),
        narrative: l.narrative.trim() || undefined })),
    }));
    if (j) {
      setNotice(`Journal for ${j.totalDebit} proposed; another person must post it.`);
      setKey(newIdempotencyKey());
      setNarrative("");
      setLines([blank("DEBIT"), blank("CREDIT")]);
      pending.reload();
    }
  }

  async function decide(j: Journal, kind: "post" | "reject") {
    const r = await action.run(() => api.post<Journal>(`${BASE}/manual-journals/${j.journalId}/${kind}`,
      { reason: (notes[j.journalId ?? ""] ?? "").trim() || undefined }));
    if (r) {
      setNotice(kind === "post" ? "Journal posted to the ledger." : "Journal rejected.");
      pending.reload();
      recent.reload();
    }
  }

  return (
    <>
      <p><Link to="/ledger">Trial balance</Link> · <Link to="/ledger/periods">Accounting periods</Link></p>
      <h1>Manual journals</h1>
      <ProblemBox problem={action.error ?? pending.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {canPropose && (
        <Section title="Propose a journal">
          <form onSubmit={(e) => void propose(e)}>
            <div className="form">
              <label>Business date<input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
              <label className="wide">Narrative<input value={narrative} onChange={(e) => setNarrative(e.target.value)} maxLength={300} /></label>
            </div>
            <table className="compact">
              <thead><tr><th>Account</th><th>Side</th><th className="num">Amount</th><th>Branch</th><th>Line narrative</th><th /></tr></thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={i}>
                    <td><input aria-label={`Account ${i + 1}`} value={l.accountCode} onChange={(e) => set(i, { accountCode: e.target.value })} /></td>
                    <td><select aria-label={`Side ${i + 1}`} value={l.side} onChange={(e) => set(i, { side: e.target.value as Side })}>
                      <option value="DEBIT">Debit</option><option value="CREDIT">Credit</option></select></td>
                    <td><input aria-label={`Amount ${i + 1}`} inputMode="decimal" value={l.amount} onChange={(e) => set(i, { amount: e.target.value })} /></td>
                    <td><input aria-label={`Branch ${i + 1}`} value={l.branchCode} onChange={(e) => set(i, { branchCode: e.target.value })} /></td>
                    <td><input aria-label={`Line narrative ${i + 1}`} value={l.narrative} onChange={(e) => set(i, { narrative: e.target.value })} /></td>
                    <td>{lines.length > 2 && <button type="button" className="secondary" onClick={() => setLines(lines.filter((_, j) => j !== i))}>Remove</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className={t.debit === t.credit ? "muted small" : "problem"}>Debits {cents(t.debit)} · Credits {cents(t.credit)}
              {t.debit !== t.credit && ` · out by ${cents(Math.abs(t.debit - t.credit))}`}</p>
            <div className="actions">
              <button type="button" className="secondary" disabled={lines.length >= 50} onClick={() => setLines([...lines, blank("DEBIT")])}>Add line</button>
              <button type="submit" disabled={action.busy || !ready}>Propose journal</button>
            </div>
            <p className="muted small">Control accounts (customer savings, loan receivables and overpayments, savings interest payable, till cash) cannot be used: correct those through the transaction itself.</p>
          </form>
        </Section>
      )}
      <Section title="Waiting to be posted">
        {pending.loading && !pending.data ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : pending.data.map((j) => (
          <div key={j.journalId} className="card">
            <p><strong>{j.businessDate}</strong> - {j.narrative} <span className="muted">({j.requestedBy}, {when(j.requestedAt)})</span></p>
            <table className="compact">
              <thead><tr><th>Account</th><th>Branch</th><th className="num">Debit</th><th className="num">Credit</th></tr></thead>
              <tbody>{(j.lines ?? []).map((l) => (
                <tr key={l.lineNo}><td>{l.accountCode} {l.accountName}</td><td>{l.branchCode}</td>
                  <td className="num">{l.side === "DEBIT" ? l.amount : ""}</td><td className="num">{l.side === "CREDIT" ? l.amount : ""}</td></tr>
              ))}</tbody>
            </table>
            {!canDecide ? null : j.requestedBy === me ? <OwnItemNote /> : (
              <span className="row-actions">
                <input aria-label={`Note for journal ${j.journalId}`} placeholder="note (needed to reject)"
                  value={notes[j.journalId ?? ""] ?? ""} onChange={(e) => setNotes({ ...notes, [j.journalId ?? ""]: e.target.value })} />
                <button type="button" disabled={action.busy} onClick={() => void decide(j, "post")}>Post journal</button>
                <button type="button" className="secondary" disabled={action.busy || (notes[j.journalId ?? ""] ?? "").trim().length < 3}
                  onClick={() => void decide(j, "reject")}>Reject</button>
              </span>
            )}
          </div>
        ))}
      </Section>
      <Section title="Recently posted">
        {!recent.data?.length ? <Empty>None yet.</Empty> : (
          <table>
            <thead><tr><th>Date</th><th>Narrative</th><th className="num">Amount</th><th>Proposed by</th><th>Posted by</th><th /></tr></thead>
            <tbody>{recent.data.map((j) => (
              <tr key={j.journalId}><td>{j.businessDate}</td><td>{j.narrative}</td><td className="num">{j.totalDebit}</td>
                <td>{j.requestedBy}</td><td>{j.decidedBy}</td><td>{j.entryId && <Link to={`/ledger/entries/${j.entryId}`}>Entry</Link>}</td></tr>
            ))}</tbody>
          </table>
        )}
      </Section>
    </>
  );
}

/** Accounting periods: open the next month and close a finished one. */
export function PeriodsPage() {
  const api = useApi();
  const { session } = useAuth();
  const canManage = hasAnyRole(session, "FINANCE");
  const periods = useLoad<Period[]>(`${BASE}/periods`);
  const action = useAction();
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);

  async function close(p: Period) {
    const r = await action.run(() => api.post<Period>(`${BASE}/periods/${p.periodId}/close`, { note: (notes[p.periodId ?? ""] ?? "").trim() || undefined }));
    if (r) { setNotice(`${r.periodId} closed.`); periods.reload(); }
  }
  async function openNext() {
    const r = await action.run(() => api.post<Period>(`${BASE}/periods/next`));
    if (r) { setNotice(`${r.periodId} opened.`); periods.reload(); }
  }

  return (
    <>
      <p><Link to="/ledger">Trial balance</Link> · <Link to="/ledger/journals">Manual journals</Link></p>
      <h1>Accounting periods</h1>
      <ProblemBox problem={action.error ?? periods.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {canManage && <p><button type="button" disabled={action.busy} onClick={() => void openNext()}>Open next month</button></p>}
      <Section title="Periods">
        {periods.loading && !periods.data ? <Loading /> : !periods.data?.length ? <Empty>No periods.</Empty> : (
          <table>
            <thead><tr><th>Period</th><th>Status</th><th>Last day closed</th><th className="num">Pending journals</th>
              <th className="num">Reconciliation differences</th><th>Closed by</th><th /></tr></thead>
            <tbody>{periods.data.map((p) => {
              const ready = p.status === "OPEN" && p.lastDayClosed && (p.pendingJournals ?? 0) === 0;
              const needsNote = (p.reconciliationDifferences ?? 0) > 0;
              return (
                <tr key={p.periodId}>
                  <td>{p.periodId}</td><td><Status value={p.status} /></td><td>{p.lastDayClosed ? "yes" : "no"}</td>
                  <td className="num">{p.pendingJournals}</td>
                  <td className="num">{p.reconciliationDifferences ?? "-"}</td>
                  <td>{p.closedBy ? `${p.closedBy}, ${when(p.closedAt)}` : ""}</td>
                  <td>{canManage && ready && (
                    <span className="row-actions">
                      {needsNote && <input aria-label={`Closing note for ${p.periodId}`} placeholder="explain the differences"
                        value={notes[p.periodId ?? ""] ?? ""} onChange={(e) => setNotes({ ...notes, [p.periodId ?? ""]: e.target.value })} />}
                      <button type="button" disabled={action.busy || (needsNote && (notes[p.periodId ?? ""] ?? "").trim().length < 10)}
                        onClick={() => void close(p)}>Close {p.periodId}</button>
                    </span>
                  )}</td>
                </tr>
              );
            })}</tbody>
          </table>
        )}
        <p className="muted small">A month closes once end of day has closed its last day and no journal dated in it is pending. A closed month accepts no postings and cannot be reopened here.</p>
      </Section>
    </>
  );
}
