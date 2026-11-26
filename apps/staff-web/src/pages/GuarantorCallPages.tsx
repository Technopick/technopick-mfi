import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Settings = Schemas["GuaranteeSettingsView"];
type LoanGuarantees = Schemas["LoanGuarantees"];
type Call = Schemas["GuarantorCallView"];

const L = "/api/v1/lending";

function Decide({ call, onDone }: { call: Call; onDone: () => void }) {
  const api = useApi();
  const action = useAction();
  const { session } = useAuth();
  const [reason, setReason] = useState("");
  if (!session || call.status !== "REQUESTED" || !hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR")) return null;
  if (call.requestedBy === actorOf(session)) return <OwnItemNote />;
  return (
    <span className="row-actions">
      <button type="button" disabled={action.busy} onClick={() => void (async () => {
        if (await action.run(() => api.post<Call>(`${L}/guarantor-calls/${call.callId}/approve`))) onDone();
      })()}>Approve and recover</button>
      <input aria-label={`Reason to reject the call on ${call.loanNumber}`} placeholder="reason to reject" value={reason}
        onChange={(e) => setReason(e.target.value)} />
      <button type="button" className="secondary" disabled={action.busy || reason.trim().length < 3} onClick={() => void (async () => {
        if (await action.run(() => api.post<Call>(`${L}/guarantor-calls/${call.callId}/reject`, { reason: reason.trim() }))) onDone();
      })()}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

function CallTable({ calls, onDone, showLoan }: { calls: Call[]; onDone: () => void; showLoan: boolean }) {
  return (
    <table>
      <thead><tr>{showLoan && <th>Loan</th>}<th>Days past due</th><th className="num">Amount</th><th>From guarantors</th><th>Reason</th>
        <th>Status</th><th>Asked by</th><th>Decided by</th><th /></tr></thead>
      <tbody>{calls.map((c) => (
        <tr key={c.callId}>
          {showLoan && <td><Link to={`/loans/${c.loanNumber}`}>{c.loanNumber}</Link><div className="muted small">{c.borrowerNumber}</div></td>}
          <td>{c.daysPastDue}</td>
          <td className="num">{formatAmount(c.amount)}</td>
          <td>{(c.lines ?? []).map((l) => (
            <div key={l.guaranteeId}>{l.guarantorNumber} {formatAmount(l.amount)} <span className="muted small">from {l.accountNumber}
              {l.applied ? ", recovered" : ""}</span></div>
          ))}</td>
          <td>{c.reason}</td>
          <td><Status value={c.status} />{c.decisionReason && <div className="muted small">{c.decisionReason}</div>}</td>
          <td>{c.requestedBy}<div className="muted small">{when(c.requestedAt)}</div></td>
          <td>{c.decidedBy ?? ""}{c.decidedAt && <div className="muted small">{when(c.decidedAt)}</div>}</td>
          <td><Decide call={c} onDone={onDone} /></td>
        </tr>
      ))}</tbody>
    </table>
  );
}

function CallForm({ g, onDone }: { g: LoanGuarantees; onDone: () => void }) {
  const api = useApi();
  const action = useAction();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.post<Call>(`${L}/loans/${g.loanNumber}/guarantor-calls`, {
      idempotencyKey: key, amount: amount.trim() === "" ? null : amount.trim(), reason: reason.trim(),
    }))) {
      setKey(newIdempotencyKey());
      setAmount("");
      setReason("");
      onDone();
    }
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Call on the guarantors</h3>
      <ProblemBox problem={action.error} />
      <label>Amount<input value={amount} inputMode="decimal" placeholder={`all that can be called: ${formatAmount(g.callable)}`}
        onChange={(e) => setAmount(e.target.value)} /></label>
      <label>Reason<input value={reason} required minLength={10} maxLength={300} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Ask for approval</button></div>
      <p className="muted small">The amount is shared among the guarantors in proportion to what each still has locked. Nothing moves until a
        branch manager or supervisor approves; then each share is taken from the guarantor's savings and paid into this loan.</p>
    </form>
  );
}

/** A loan's guarantors and calls on them (ADR-0044), shown on the loan page. */
export function LoanGuarantorsPanel({ loanNumber, onChange }: { loanNumber: string; onChange: () => void }) {
  const { session } = useAuth();
  const guarantees = useLoad<LoanGuarantees>(`${L}/loans/${encodeURIComponent(loanNumber)}/guarantees`);
  const g = guarantees.data;
  const calls = useLoad<Call[]>(g?.guarantees?.length ? `${L}/guarantor-calls` : null);
  const reload = () => {
    guarantees.reload();
    calls.reload();
    onChange();
  };
  if (guarantees.loading && !g) return <Loading />;
  if (!g) return <ProblemBox problem={guarantees.error} />;
  if (!g.guarantees?.length) return null;
  const mine = (calls.data ?? []).filter((c) => c.loanNumber === loanNumber);
  const waiting = mine.some((c) => c.status === "REQUESTED");
  const canAsk = g.callAllowed && !waiting && hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS");
  return (
    <Section title="Guarantors">
      <dl className="facts">
        <div><dt>Days past due</dt><dd>{g.daysPastDue}<div className="muted small">at the last close; guarantors are called from
          {" "}{g.minDaysPastDue} days</div></dd></div>
        <div><dt>Still locked</dt><dd>{formatAmount(g.locked)}</dd></div>
        <div><dt>Owed on the loan</dt><dd>{formatAmount(g.owed)}</dd></div>
        <div><dt>Can be called</dt><dd>{formatAmount(g.callable)}</dd></div>
      </dl>
      <table>
        <thead><tr><th>Guarantor</th><th>Account</th><th className="num">Pledged</th><th className="num">Still locked</th>
          <th className="num">Recovered</th><th>Status</th></tr></thead>
        <tbody>{g.guarantees.map((x) => (
          <tr key={x.guaranteeId}>
            <td>{x.guarantorNumber}<div className="muted small">{x.guarantorName}</div></td><td>{x.accountNumber}</td>
            <td className="num">{formatAmount(x.pledged)}</td><td className="num">{formatAmount(x.locked)}</td>
            <td className="num">{formatAmount(x.recovered)}</td><td><Status value={x.status} /></td>
          </tr>
        ))}</tbody>
      </table>
      {mine.length > 0 && <CallTable calls={mine} onDone={reload} showLoan={false} />}
      {canAsk && <CallForm g={g} onDone={reload} />}
    </Section>
  );
}

function SettingsForm({ current, onSaved }: { current: Settings | null; onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const [clearing, setClearing] = useState(current?.recoveryClearingAccount ?? "");
  const [days, setDays] = useState(String(current?.minDaysPastDue ?? 90));
  const [release, setRelease] = useState(current?.releaseAsRepaid ?? false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.put<Settings>(`${L}/guarantee-settings`, {
      recoveryClearingAccount: clearing.trim(), minDaysPastDue: Number(days), releaseAsRepaid: release,
    }))) onSaved();
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <ProblemBox problem={action.error} />
      <label>Recoveries clearing account (an asset account)<input value={clearing} required maxLength={40}
        onChange={(e) => setClearing(e.target.value)} /></label>
      <label>Call guarantors from (days past due)<input value={days} required type="number" min={1} max={3650}
        onChange={(e) => setDays(e.target.value)} /></label>
      <label className="check"><input type="checkbox" checked={release} onChange={(e) => setRelease(e.target.checked)} />
        Release guarantees as loans are repaid</label>
      <div className="actions"><button type="submit" disabled={action.busy}>Save</button></div>
      <p className="muted small">The clearing account cannot be changed once a call has been made. With release as repaid, each night's close
        lowers each guarantor's lock to their share of the principal still outstanding.</p>
    </form>
  );
}

/** Calls on guarantors waiting for a decision, and how guarantor calls are set up (ADR-0044). */
export function GuarantorCallsPage() {
  const { session } = useAuth();
  const [status, setStatus] = useState("REQUESTED");
  const settings = useLoad<Settings>(`${L}/guarantee-settings`);
  const calls = useLoad<Call[]>(`${L}/guarantor-calls${status ? `?status=${status}` : ""}`);
  const isFinance = hasAnyRole(session, "FINANCE");
  const s = settings.data;
  return (
    <>
      <h1>Guarantor calls</h1>
      <p className="muted">When a loan is far enough past due, a loan officer can ask to call on its guarantors. Once a branch manager or
        supervisor approves, each guarantor's share is taken from their savings and paid into the loan.</p>
      <Section title="Set-up">
        {settings.loading && !s ? <Loading /> : s ? (
          <dl className="facts">
            <div><dt>Recoveries clearing</dt><dd>{s.recoveryClearingAccount}</dd></div>
            <div><dt>Call from</dt><dd>{s.minDaysPastDue} days past due</dd></div>
            <div><dt>Release as repaid</dt><dd>{s.releaseAsRepaid ? "Yes" : "No"}</dd></div>
            <div><dt>Changed by</dt><dd>{s.updatedBy}<div className="muted small">{when(s.updatedAt)}</div></dd></div>
          </dl>
        ) : <p className="muted">Guarantor calls are not set up yet.{isFinance ? "" : " Finance sets them up."}</p>}
        {isFinance && !settings.loading && <SettingsForm key={s?.updatedAt ?? "new"} current={s ?? null} onSaved={() => settings.reload()} />}
      </Section>
      <Section title="Calls" actions={
        <label>Show<select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="REQUESTED">Waiting for a decision</option><option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option><option value="">All</option>
        </select></label>}>
        <ProblemBox problem={calls.error} />
        {calls.loading && !calls.data ? <Loading /> : !calls.data?.length ? <Empty>No calls.</Empty> : (
          <CallTable calls={calls.data} onDone={() => calls.reload()} showLoan />
        )}
      </Section>
    </>
  );
}
