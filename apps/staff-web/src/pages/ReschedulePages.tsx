import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type Preview = Schemas["ReschedulePreview"];
type Reschedule = Schemas["RescheduleView"];
type Row = Schemas["ScheduleRow"];

const KIND_TEXT: Record<string, string> = { RESTRUCTURE: "Restructure", PREPAYMENT: "Partial prepayment" };
const MODE_TEXT: Record<string, string> = { SHORTEN_TERM: "shorter term", REDUCE_INSTALMENT: "lower instalment" };

function describe(r: Reschedule): string {
  return r.kind === "RESTRUCTURE"
    ? `${r.instalments} instalments${r.graceMonths ? ` after ${r.graceMonths} interest-only month(s)` : ""}`
    : `KES ${r.prepayment} prepaid, ${MODE_TEXT[r.prepaymentMode ?? ""] ?? r.prepaymentMode}`;
}

function Rows({ rows, caption }: { rows: Row[]; caption: string }) {
  return (
    <table className="compact">
      <caption>{caption}</caption>
      <thead><tr><th>#</th><th>Due</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Instalment</th></tr></thead>
      <tbody>{rows.map((r) => (
        <tr key={r.no}><td>{r.no}</td><td>{r.dueDate}</td><td className="num"><Money amount={r.principal} /></td>
          <td className="num"><Money amount={r.interest} /></td><td className="num"><Money amount={r.total} /></td></tr>
      ))}</tbody>
    </table>
  );
}

function Decision({ r, onDone }: { r: Reschedule; onDone: (v: Reschedule) => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (!hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR")) return null;
  if (session && r.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    const v = await action.run(() => api.post<Reschedule>(`/api/v1/lending/reschedules/${r.rescheduleId}/${kind}`,
      { reason: note.trim() || undefined }));
    if (v) onDone(v);
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for reschedule of ${r.loanNumber}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve new schedule</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

/** Restructure or partial prepayment for one loan: preview, request, decide, history (ADR-0037). */
export function ReschedulePanel({ loanNumber, onChange }: { loanNumber: string; onChange?: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const history = useLoad<Reschedule[]>(`/api/v1/lending/loans/${encodeURIComponent(loanNumber)}/reschedules`);
  const previewAction = useAction();
  const requestAction = useAction();
  const [kind, setKind] = useState("RESTRUCTURE");
  const [instalments, setInstalments] = useState("12");
  const [grace, setGrace] = useState("0");
  const [amount, setAmount] = useState("");
  const [mode, setMode] = useState("SHORTEN_TERM");
  const [reason, setReason] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [key, setKey] = useState(newIdempotencyKey);
  const [notice, setNotice] = useState<string | null>(null);
  const canRequest = hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER");
  const canPreview = hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER", "SUPERVISOR");
  const terms = kind === "RESTRUCTURE"
    ? { kind, instalments: Number(instalments), graceMonths: Number(grace) }
    : { kind, amount: amount.trim(), mode };
  const pending = history.data?.find((r) => r.status === "REQUESTED");

  async function showPreview(e: FormEvent) {
    e.preventDefault();
    setNotice(null);
    const p = await previewAction.run(() => api.post<Preview>(`/api/v1/lending/loans/${encodeURIComponent(loanNumber)}/reschedules/preview`, terms));
    setPreview(p ?? null);
  }

  async function submit() {
    const r = await requestAction.run(() => api.post<Reschedule>(`/api/v1/lending/loans/${encodeURIComponent(loanNumber)}/reschedules`,
      { ...terms, idempotencyKey: key, reason: reason.trim() }));
    if (r) {
      setNotice(`${KIND_TEXT[r.kind ?? ""]} requested; a branch manager or supervisor must approve it today.`);
      setPreview(null);
      setReason("");
      setKey(newIdempotencyKey());
      history.reload();
    }
  }

  async function showPending(r: Reschedule) {
    const t = r.kind === "RESTRUCTURE" ? { kind: r.kind, instalments: r.instalments, graceMonths: r.graceMonths }
      : { kind: r.kind, amount: r.prepayment, mode: r.prepaymentMode };
    const p = await previewAction.run(() => api.post<Preview>(`/api/v1/lending/loans/${encodeURIComponent(loanNumber)}/reschedules/preview`, t));
    setPreview(p ?? null);
  }

  function decided(v: Reschedule) {
    setPreview(null);
    setNotice(v.status === "APPROVED" ? `New schedule approved (version ${v.toVersion}).` : "Reschedule rejected.");
    history.reload();
    onChange?.();
  }

  if (!canPreview && !history.data?.length) return null;
  return (
    <Section title="Restructure or prepay">
      <ProblemBox problem={history.error ?? previewAction.error ?? requestAction.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {pending && (
        <div className="decision">
          <p>{KIND_TEXT[pending.kind ?? ""]} requested by {pending.requestedBy} on {pending.businessDate}: {describe(pending)}.
            {" "}{pending.reason}</p>
          {canPreview && <p><button type="button" className="secondary" disabled={previewAction.busy}
            onClick={() => void showPending(pending)}>Show proposed schedule</button></p>}
          <Decision r={pending} onDone={decided} />
        </div>
      )}
      {canPreview && !pending && (
        <form className="form" onSubmit={(e) => void showPreview(e)}>
          <label>Change
            <select value={kind} onChange={(e) => { setKind(e.target.value); setPreview(null); }}>
              <option value="RESTRUCTURE">Restructure (new number of instalments)</option>
              <option value="PREPAYMENT">Partial prepayment (from the member's credit)</option>
            </select>
          </label>
          {kind === "RESTRUCTURE" ? (
            <>
              <label>Instalments<input type="number" min={1} max={120} value={instalments} onChange={(e) => setInstalments(e.target.value)} /></label>
              <label>Interest-only months first<input type="number" min={0} max={12} value={grace} onChange={(e) => setGrace(e.target.value)} /></label>
            </>
          ) : (
            <>
              <label>Prepayment (KES)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="3000.00" /></label>
              <label>Use it to
                <select value={mode} onChange={(e) => setMode(e.target.value)}>
                  <option value="SHORTEN_TERM">Shorten the term</option>
                  <option value="REDUCE_INSTALMENT">Lower the instalment</option>
                </select>
              </label>
            </>
          )}
          <div className="actions"><button type="submit" className="secondary" disabled={previewAction.busy}>Preview new schedule</button></div>
        </form>
      )}
      {preview && (
        <>
          <dl className="facts">
            <div><dt>Principal outstanding</dt><dd><Money amount={preview.outstandingPrincipal} /></dd></div>
            <div><dt>Still due now (overdue interest and penalties)</dt><dd><Money amount={preview.stillDueNow} /></dd></div>
            <div><dt>Instalments</dt><dd>{preview.instalments}, usually <Money amount={preview.instalmentAmount} />, first due {preview.firstDue}</dd></div>
            <div><dt>Interest still to come</dt><dd><Money amount={preview.interestBefore} /> now, <Money amount={preview.interestAfter} /> after</dd></div>
            {preview.kind === "PREPAYMENT" && <div><dt>Member's credit</dt><dd><Money amount={preview.credit} /></dd></div>}
          </dl>
          <Rows rows={preview.proposed ?? []} caption="Proposed schedule from the next instalment" />
          {preview.kind === "PREPAYMENT" && (
            <p className="muted small">The prepayment is added to the next instalment and paid from the member's credit on its due date.</p>
          )}
          {canRequest && !pending && (
            <div className="form">
              <label className="wide">Why, and what the member agreed
                <input value={reason} onChange={(e) => setReason(e.target.value)} minLength={10} maxLength={300} />
              </label>
              <div className="actions">
                <button type="button" disabled={requestAction.busy || reason.trim().length < 10} onClick={() => void submit()}>
                  Request {KIND_TEXT[preview.kind ?? ""]?.toLowerCase()}</button>
              </div>
            </div>
          )}
        </>
      )}
      {history.loading && !history.data ? <Loading /> : history.data && history.data.length > 0 && (
        <table>
          <caption>History</caption>
          <thead><tr><th>Date</th><th>Change</th><th>Status</th><th>Requested</th><th>Decided</th></tr></thead>
          <tbody>{history.data.map((r) => (
            <tr key={r.rescheduleId}><td>{r.businessDate}</td><td>{KIND_TEXT[r.kind ?? ""]}: {describe(r)}</td>
              <td><Status value={r.status ?? ""} /></td><td>{r.requestedBy}</td>
              <td>{r.decidedBy}{r.decisionReason && <div className="muted small">{r.decisionReason}</div>}</td></tr>
          ))}</tbody>
        </table>
      )}
    </Section>
  );
}

/** Reschedules waiting for a branch manager or supervisor. */
export function ReschedulesPage() {
  const pending = useLoad<Reschedule[]>("/api/v1/lending/reschedules");
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <>
      <h1>Loan reschedules waiting for approval</h1>
      <ProblemBox problem={pending.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <Section title="Waiting">
        {pending.loading && !pending.data ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <table>
            <thead><tr><th>Loan</th><th>Change</th><th>Reason</th><th>Requested</th><th /></tr></thead>
            <tbody>{pending.data.map((r) => (
              <tr key={r.rescheduleId}>
                <td><Link to={`/loans/${r.loanNumber}`}>{r.loanNumber}</Link></td>
                <td>{KIND_TEXT[r.kind ?? ""]}: {describe(r)}</td><td>{r.reason}</td>
                <td>{r.requestedBy}<div className="muted small">{when(r.requestedAt)}</div></td>
                <td><Decision r={r} onDone={(v) => { setNotice(`${v.loanNumber}: ${v.status === "APPROVED" ? "new schedule approved" : "rejected"}.`); pending.reload(); }} /></td>
              </tr>
            ))}</tbody>
          </table>
        )}
        <p className="muted small">Open each loan to see the proposed schedule before approving. Approval must be on the day of the request.</p>
      </Section>
    </>
  );
}
