import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type WriteOff = Schemas["WriteOffView"];
const BASE = "/api/v1/lending";

/** Request a loan write-off (manager / operations) and decide pending ones (supervisor). */
export function WriteOffsPage() {
  const api = useApi();
  const { session } = useAuth();
  const canRequest = hasAnyRole(session, "BRANCH_MANAGER", "OPERATIONS");
  const canDecide = hasAnyRole(session, "SUPERVISOR");
  const me = session ? actorOf(session) : "";
  const pending = useLoad<WriteOff[]>(`${BASE}/write-offs?status=PENDING`);
  const approved = useLoad<WriteOff[]>(`${BASE}/write-offs?status=APPROVED`);
  const action = useAction();
  const [loanNumber, setLoanNumber] = useState("");
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const note = (id?: string) => (notes[id ?? ""] ?? "").trim();

  async function request(e: FormEvent) {
    e.preventDefault();
    const w = await action.run(() => api.post<WriteOff>(`${BASE}/loans/${encodeURIComponent(loanNumber.trim())}/write-off`,
      { idempotencyKey: key, reason: reason.trim() }));
    if (w) {
      setNotice(`Write-off of ${w.loanNumber} requested; a supervisor must approve it.`);
      setKey(newIdempotencyKey());
      setLoanNumber("");
      setReason("");
      pending.reload();
    }
  }

  async function decide(w: WriteOff, kind: "approve" | "reject") {
    const r = await action.run(() => api.post<WriteOff>(`${BASE}/write-offs/${w.writeOffId}/${kind}`,
      { reason: note(w.writeOffId) || undefined }));
    if (r) {
      setNotice(kind === "approve" ? `${r.loanNumber} written off: ${r.writtenOff} ${r.currency}.` : `Write-off of ${r.loanNumber} rejected.`);
      pending.reload();
      approved.reload();
    }
  }

  return (
    <>
      <h1>Loan write-offs</h1>
      <ProblemBox problem={action.error ?? pending.error ?? approved.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {canRequest && (
        <Section title="Request a write-off">
          <form className="form" onSubmit={(e) => void request(e)}>
            <label>Loan number<input value={loanNumber} onChange={(e) => setLoanNumber(e.target.value)} required /></label>
            <label className="wide">Reason<input value={reason} onChange={(e) => setReason(e.target.value)} minLength={10} maxLength={300} required /></label>
            <div className="actions">
              <button type="submit" disabled={action.busy || !loanNumber.trim() || reason.trim().length < 10}>Request write-off</button>
            </div>
          </form>
          <p className="muted small">Only loans that the last end of day showed at least the institution's minimum days past due, and that hold no overpaid money, can be written off.</p>
        </Section>
      )}
      <Section title="Waiting for approval">
        {pending.loading && !pending.data ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <table>
            <thead><tr><th>Loan</th><th className="num">Days past due</th><th className="num">Principal</th><th className="num">Interest</th>
              <th className="num">Penalties</th><th>Reason</th><th>Requested by</th><th /></tr></thead>
            <tbody>
              {pending.data.map((w) => (
                <tr key={w.writeOffId}>
                  <td>{w.loanNumber}</td><td className="num">{w.daysPastDue}</td>
                  <td className="num"><Money amount={w.principalOutstanding} currency={w.currency} /></td>
                  <td className="num"><Money amount={w.interestOutstanding} currency={w.currency} /></td>
                  <td className="num"><Money amount={w.penaltyOutstanding} currency={w.currency} /></td>
                  <td>{w.reason}</td><td>{w.requestedBy} - {when(w.requestedAt)}</td>
                  <td>{!canDecide ? null : w.requestedBy === me ? <OwnItemNote /> : (
                    <span className="row-actions">
                      <input aria-label={`Note for ${w.loanNumber}`} placeholder="note (needed to reject)"
                        value={notes[w.writeOffId ?? ""] ?? ""} onChange={(e) => setNotes({ ...notes, [w.writeOffId ?? ""]: e.target.value })} />
                      <button type="button" disabled={action.busy} onClick={() => void decide(w, "approve")}>Approve write-off</button>
                      <button type="button" className="secondary" disabled={action.busy || note(w.writeOffId).length < 3}
                        onClick={() => void decide(w, "reject")}>Reject</button>
                    </span>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted small">Approval writes off what the loan owes at that moment, which can differ from the figures shown if end of day has run since.</p>
      </Section>
      <Section title="Written off">
        {approved.loading && !approved.data ? <Loading /> : !approved.data?.length ? <Empty>No loan has been written off.</Empty> : (
          <table>
            <thead><tr><th>Loan</th><th>Status</th><th className="num">Written off</th><th className="num">Recovered</th><th>Approved by</th></tr></thead>
            <tbody>
              {approved.data.map((w) => (
                <tr key={w.writeOffId}>
                  <td>{w.loanNumber}</td><td><Status value={w.status} /></td>
                  <td className="num"><Money amount={w.writtenOff} currency={w.currency} /></td>
                  <td className="num"><Money amount={w.recovered} currency={w.currency} /></td>
                  <td>{w.decidedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
