import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { isAmount } from "../money";

type ItemType = "STK_REQUEST" | "B2C_DISBURSEMENT";
type StatusQuery = Schemas["StatusQueryView"];

const QUERY_OUTCOME: Record<string, string> = {
  COMPLETED_DISBURSEMENT: "Paid - disbursement completed",
  AMOUNT_MISMATCH: "Paid a different amount - not posted",
  ALREADY_FINAL: "Outcome already known",
  NOT_CONFIRMED: "Payment not confirmed",
};

/** The latest M-Pesa Transaction Status answer for one disbursement, and a button to ask again (ADR-0039). */
function StatusQueryCell({ disbursementId, canAsk, onAnswered }: { disbursementId: string; canAsk: boolean; onAnswered: () => void }) {
  const api = useApi();
  const queries = useLoad<StatusQuery[]>(`/api/v1/payments/mpesa/b2c/disbursements/${disbursementId}/status-queries`);
  const action = useAction();
  const latest = queries.data?.[0];
  const waiting = latest?.status === "PENDING" || latest?.status === "SENT";
  async function ask() {
    const q = await action.run(() => api.post<StatusQuery>(`/api/v1/payments/mpesa/b2c/disbursements/${disbursementId}/status-queries`));
    if (q) {
      queries.reload();
      onAnswered();
    }
  }
  let text = "Not asked yet";
  if (latest) {
    if (waiting) text = `Asked ${when(latest.requestedAt)} - waiting for M-Pesa`;
    else if (latest.status === "ANSWERED") {
      text = `${QUERY_OUTCOME[latest.outcome ?? ""] ?? latest.outcome}`
        + (latest.reportedReceipt ? ` (${latest.reportedReceipt}, ${latest.reportedAmount})` : ` (code ${latest.resultCode})`);
    } else text = latest.status === "SEND_FAILED" ? `Could not ask: ${latest.resultDesc ?? ""}` : "No answer from M-Pesa";
  }
  return (
    <div>
      <span className="small">{text}</span>
      {(queries.data?.length ?? 0) > 1 && <span className="muted small"> · asked {queries.data?.length} times</span>}
      <ProblemBox problem={action.error ?? queries.error} />
      {canAsk && (
        <div><button type="button" className="secondary" disabled={action.busy || waiting} onClick={() => void ask()}>Ask M-Pesa now</button></div>
      )}
    </div>
  );
}
interface Target { itemType: ItemType; itemId: string; label: string }

function ProposeForm({ target, onDone, onCancel }: { target: Target; onDone: () => void; onCancel: () => void }) {
  const api = useApi();
  const { busy, error, run } = useAction();
  const [outcome, setOutcome] = useState<"PAID" | "NOT_PAID">("PAID");
  const [providerRef, setProviderRef] = useState("");
  const [amount, setAmount] = useState("");
  const [evidence, setEvidence] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (outcome === "PAID" && (!/^[A-Z0-9]{8,20}$/.test(providerRef.trim().toUpperCase()) || !isAmount(amount))) {
      setInvalid("PAID needs the M-Pesa receipt / transaction number and the amount exactly as on the statement.");
      return;
    }
    setInvalid(null);
    const done = await run(() => api.post("/api/v1/payments/mpesa/resolutions", {
      itemType: target.itemType, itemId: target.itemId, outcome,
      providerRef: outcome === "PAID" ? providerRef.trim().toUpperCase() : undefined,
      amount: outcome === "PAID" ? amount.trim() : undefined,
      evidence: evidence.trim(),
    }));
    if (done) onDone();
  }
  return (
    <form onSubmit={submit} className="form inset" aria-label={`Propose outcome for ${target.label}`}>
      <p className="muted wide">Look the payment up in the M-Pesa org portal first. A supervisor must approve your proposal.</p>
      <label>Outcome
        <select value={outcome} onChange={(e) => setOutcome(e.target.value as "PAID" | "NOT_PAID")}>
          <option value="PAID">Paid</option>
          <option value="NOT_PAID">Not paid</option>
        </select>
      </label>
      {outcome === "PAID" && <>
        <label>Receipt / transaction number<input value={providerRef} onChange={(e) => setProviderRef(e.target.value)} /></label>
        <label>Amount (KES)<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
      </>}
      <label className="wide">Evidence (statement line, time, who checked)<input required minLength={10} value={evidence} onChange={(e) => setEvidence(e.target.value)} /></label>
      {invalid && <div className="problem" role="alert">{invalid}</div>}
      <ProblemBox problem={error} />
      <div className="actions">
        <button type="submit" disabled={busy}>Submit for approval</button>
        <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

export function MpesaPage() {
  const api = useApi();
  const { session } = useAuth();
  const stk = useLoad<Schemas["AttentionItem"][]>("/api/v1/payments/mpesa/stk-requests/attention");
  const b2c = useLoad<Schemas["DisbursementView"][]>("/api/v1/payments/mpesa/b2c/disbursements/attention");
  const pending = useLoad<Schemas["MpesaResolutionView"][]>("/api/v1/payments/mpesa/resolutions");
  const action = useAction();
  const [target, setTarget] = useState<Target | null>(null);
  const canPropose = hasAnyRole(session, "OPERATIONS");
  const canDecide = hasAnyRole(session, "SUPERVISOR");
  const canAsk = hasAnyRole(session, "OPERATIONS", "SUPERVISOR");
  const proposed = new Set((pending.data ?? []).map((r) => r.itemId ?? ""));
  const reloadAll = () => { stk.reload(); b2c.reload(); pending.reload(); };

  async function decide(id: string, kind: "approve" | "reject") {
    const done = await action.run(() => api.post(`/api/v1/payments/mpesa/resolutions/${id}/${kind}`));
    if (done) reloadAll();
  }

  const proposeButton = (t: Target) => canPropose && !proposed.has(t.itemId) && (
    <button type="button" onClick={() => setTarget(t)}>Propose outcome</button>
  );

  return (
    <>
      <h1>M-Pesa close-out</h1>
      {target && <Section title={`Propose outcome - ${target.label}`}>
        <ProposeForm target={target} onCancel={() => setTarget(null)} onDone={() => { setTarget(null); reloadAll(); }} />
      </Section>}
      <Section title="Close-outs awaiting approval">
        <ProblemBox problem={pending.error ?? action.error} />
        {pending.loading ? <Loading /> : !pending.data?.length ? <Empty>No proposals are waiting.</Empty> : (
          <table>
            <thead><tr><th>Item</th><th>Outcome</th><th>Receipt</th><th className="num">Amount</th><th>Evidence</th><th>Proposed by</th><th /></tr></thead>
            <tbody>
              {pending.data.map((r) => (
                <tr key={r.resolutionId}>
                  <td>{r.itemType === "STK_REQUEST" ? "STK request" : "Disbursement"}</td>
                  <td><Status value={r.outcome} /></td>
                  <td>{r.providerRef}</td>
                  <td className="num"><Money amount={r.amount} /></td>
                  <td>{r.evidence}</td>
                  <td>{r.requestedBy} - {when(r.requestedAt)}</td>
                  <td>{canDecide && (session && r.requestedBy === actorOf(session) ? <OwnItemNote /> : (
                    <span className="row-actions">
                      <button type="button" disabled={action.busy} onClick={() => void decide(r.resolutionId ?? "", "approve")}>Approve</button>
                      <button type="button" className="secondary" disabled={action.busy} onClick={() => void decide(r.resolutionId ?? "", "reject")}>Reject</button>
                    </span>
                  ))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <Section title="STK requests needing attention">
        <ProblemBox problem={stk.error} />
        {stk.loading ? <Loading /> : !stk.data?.length ? <Empty>Nothing needs attention.</Empty> : (
          <table>
            <thead><tr><th>For</th><th className="num">Amount</th><th>Phone</th><th>Reason</th><th>Requested</th><th /></tr></thead>
            <tbody>
              {stk.data.map((s) => (
                <tr key={s.requestId}>
                  <td>{s.targetReference}</td>
                  <td className="num"><Money amount={s.amount} /></td>
                  <td>{s.msisdnMasked}</td>
                  <td><Status value={s.attention} /></td>
                  <td>{when(s.requestedAt)}</td>
                  <td>{proposeButton({ itemType: "STK_REQUEST", itemId: s.requestId ?? "", label: `STK ${s.targetReference ?? ""}` })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <Section title="Disbursements with an unknown outcome">
        <ProblemBox problem={b2c.error} />
        {b2c.loading ? <Loading /> : !b2c.data?.length ? <Empty>Nothing needs attention.</Empty> : (
          <table>
            <thead><tr><th>Loan</th><th className="num">Amount</th><th>To</th><th>Reason</th><th>Conversation</th><th>M-Pesa transaction status</th><th /></tr></thead>
            <tbody>
              {b2c.data.map((d) => {
                const resolvable = d.attention === "TIMEOUT_UNKNOWN" || d.attention === "SEND_OUTCOME_UNKNOWN";
                return (
                  <tr key={d.disbursementId}>
                    <td>{d.loanNumber}</td>
                    <td className="num"><Money amount={d.amount} currency={d.currency} /></td>
                    <td>{d.msisdnMasked}</td>
                    <td><Status value={d.attention} /></td>
                    <td className="mono">{d.conversationId ?? d.disbursementId}</td>
                    <td><StatusQueryCell disbursementId={d.disbursementId ?? ""} onAnswered={reloadAll}
                      canAsk={canAsk && (d.status === "DISPATCHED" || d.status === "SUBMITTED")} /></td>
                    <td>{resolvable ? proposeButton({ itemType: "B2C_DISBURSEMENT", itemId: d.disbursementId ?? "", label: `disbursement ${d.loanNumber ?? ""}` })
                      : <span className="muted">Finance</span>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
