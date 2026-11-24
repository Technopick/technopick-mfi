import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type Quote = Schemas["SettlementQuote"];
type Settlement = Schemas["SettlementView"];
type Refund = Schemas["RefundView"];
const BASE = "/api/v1/lending";

/** Payoff quote and early settlement of one loan; refund requests for credit left on a closed loan (ADR-0032). */
export function SettlementPanel({ loanNumber, onChange }: { loanNumber: string; onChange?: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const canAct = hasAnyRole(session, "BRANCH_MANAGER", "OPERATIONS");
  const quote = useLoad<Quote>(`${BASE}/loans/${encodeURIComponent(loanNumber)}/settlement`);
  const refunds = useLoad<Refund[]>(canAct || hasAnyRole(session, "SUPERVISOR")
    ? `${BASE}/credit-refunds?loanNumber=${encodeURIComponent(loanNumber)}` : null);
  const action = useAction();
  const [key] = useState(newIdempotencyKey);
  const [refundKey, setRefundKey] = useState(newIdempotencyKey);
  const [account, setAccount] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const q = quote.data;
  const ccy = q?.currency;

  async function settle() {
    const s = await action.run(() => api.post<Settlement>(`${BASE}/loans/${encodeURIComponent(loanNumber)}/settlement`, { idempotencyKey: key }));
    if (s) {
      setNotice(`Loan settled: ${s.payoff} paid off; ${s.interestWaived} of scheduled interest not charged; ${s.creditLeft} of credit left.`);
      quote.reload();
      refunds.reload();
      onChange?.();
    }
  }

  async function requestRefund(e: FormEvent) {
    e.preventDefault();
    const r = await action.run(() => api.post<Refund>(`${BASE}/loans/${encodeURIComponent(loanNumber)}/credit-refunds`,
      { idempotencyKey: refundKey, accountNumber: account.trim(), amount: amount.trim(), reason: reason.trim() }));
    if (r) {
      setNotice(`Refund of ${r.amount} to ${r.accountNumber} requested; a supervisor must approve it.`);
      setRefundKey(newIdempotencyKey());
      setAccount("");
      setAmount("");
      setReason("");
      refunds.reload();
    }
  }

  if (quote.loading && !q) return <Loading />;
  return (
    <>
      <ProblemBox problem={quote.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {q?.status === "ACTIVE" && (
        <Section title="Early settlement">
          <dl className="facts">
            <dt>Principal outstanding</dt><dd><Money amount={q.principal} currency={ccy} /></dd>
            <dt>Interest accrued and unpaid</dt><dd><Money amount={q.interest} currency={ccy} /></dd>
            <dt>Penalties unpaid</dt><dd><Money amount={q.penalty} currency={ccy} /></dd>
            <dt>Payoff on {q.businessDate}</dt><dd><strong><Money amount={q.payoff} currency={ccy} /></strong></dd>
            <dt>Scheduled interest not charged</dt><dd><Money amount={q.interestWaived} currency={ccy} /></dd>
            <dt>Already paid towards it</dt><dd><Money amount={q.creditHeld} currency={ccy} /></dd>
            <dt>Still to pay</dt><dd><Money amount={q.stillToPay} currency={ccy} /></dd>
          </dl>
          {q.canSettle
            ? canAct && <button type="button" disabled={action.busy} onClick={() => void settle()}>Settle loan now</button>
            : <p className="muted small">The member pays the amount still to pay to the loan number (M-Pesa); the loan can then be settled.
              The payoff covers interest up to the last end of day.</p>}
        </Section>
      )}
      {q?.settlement && (
        <Section title="Settled early">
          <p>On {q.settlement.businessDate} by {q.settlement.settledBy}: <Money amount={q.settlement.payoff} currency={ccy} /> paid off,
            {" "}<Money amount={q.settlement.interestWaived} currency={ccy} /> of scheduled interest not charged.</p>
        </Section>
      )}
      {q && q.status === "CLOSED" && (
        <Section title="Credit on this loan">
          <p>Credit held: <strong><Money amount={q.creditHeld} currency={ccy} /></strong></p>
          {canAct && Number(q.creditHeld) > 0 && (
            <form className="form" onSubmit={(e) => void requestRefund(e)}>
              <label>Member's savings account<input value={account} onChange={(e) => setAccount(e.target.value)} required maxLength={34} /></label>
              <label>Amount<input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required /></label>
              <label className="wide">Reason<input value={reason} onChange={(e) => setReason(e.target.value)} minLength={10} maxLength={300} required /></label>
              <div className="actions"><button type="submit" disabled={action.busy || reason.trim().length < 10}>Request refund to savings</button></div>
            </form>
          )}
          {refunds.data && refunds.data.length > 0 && <RefundTable rows={refunds.data} />}
        </Section>
      )}
    </>
  );
}

function RefundTable({ rows, decide }: { rows: Refund[]; decide?: (r: Refund) => React.ReactNode }) {
  return (
    <table>
      <thead><tr><th>Loan</th><th className="num">Amount</th><th>To savings</th><th>Reason</th><th>Requested by</th><th>Status</th><th /></tr></thead>
      <tbody>{rows.map((r) => (
        <tr key={r.refundId}>
          <td>{r.loanNumber}</td><td className="num"><Money amount={r.amount} currency={r.currency} /></td><td>{r.accountNumber}</td>
          <td>{r.reason}</td><td>{r.requestedBy} - {when(r.requestedAt)}</td>
          <td><Status value={r.status} />{r.decidedBy && <div className="muted small">{r.decidedBy}</div>}</td>
          <td>{decide?.(r)}</td>
        </tr>
      ))}</tbody>
    </table>
  );
}

/** Supervisors decide refunds of loan credit; managers and operations see them. */
export function CreditRefundsPage() {
  const api = useApi();
  const { session } = useAuth();
  const canDecide = hasAnyRole(session, "SUPERVISOR");
  const me = session ? actorOf(session) : "";
  const pending = useLoad<Refund[]>(`${BASE}/credit-refunds?status=REQUESTED`);
  const done = useLoad<Refund[]>(`${BASE}/credit-refunds?status=APPROVED`);
  const action = useAction();
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const note = (id?: string) => (notes[id ?? ""] ?? "").trim();

  async function decide(r: Refund, kind: "approve" | "reject") {
    const res = await action.run(() => api.post<Refund>(`${BASE}/credit-refunds/${r.refundId}/${kind}`, { reason: note(r.refundId) || undefined }));
    if (res) {
      setNotice(kind === "approve" ? `${res.amount} paid into ${res.accountNumber} from loan ${res.loanNumber}.` : `Refund for ${res.loanNumber} rejected.`);
      pending.reload();
      done.reload();
    }
  }

  return (
    <>
      <h1>Credit refunds</h1>
      <ProblemBox problem={action.error ?? pending.error ?? done.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <Section title="Waiting for approval">
        {pending.loading && !pending.data ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <RefundTable rows={pending.data} decide={(r) => !canDecide ? null : r.requestedBy === me ? <OwnItemNote /> : (
            <span className="row-actions">
              <input aria-label={`Note for refund on ${r.loanNumber}`} placeholder="note (needed to reject)" value={notes[r.refundId ?? ""] ?? ""}
                onChange={(e) => setNotes({ ...notes, [r.refundId ?? ""]: e.target.value })} />
              <button type="button" disabled={action.busy} onClick={() => void decide(r, "approve")}>Approve refund</button>
              <button type="button" className="secondary" disabled={action.busy || note(r.refundId).length < 3}
                onClick={() => void decide(r, "reject")}>Reject</button>
            </span>
          )} />
        )}
        <p className="muted small">Approval pays the amount into the member's savings account at once, if the loan still holds that much credit.</p>
      </Section>
      <Section title="Paid">
        {done.loading && !done.data ? <Loading /> : !done.data?.length ? <Empty>No refunds yet.</Empty> : <RefundTable rows={done.data} />}
      </Section>
    </>
  );
}
