import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type View = Schemas["DisbursementView"];
const BASE = "/api/v1/payments/mpesa/b2c/disbursements";

function RequestForm({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const { busy, error, run } = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [loanNumber, setLoanNumber] = useState("");
  const [phone, setPhone] = useState("");
  const [created, setCreated] = useState<Schemas["DisbursementCreated"] | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await run(() => api.post<Schemas["DisbursementCreated"]>(BASE, {
      idempotencyKey: key, loanNumber: loanNumber.trim(), phoneNumber: phone.trim() || undefined,
    }));
    if (r) {
      setCreated(r);
      setKey(newIdempotencyKey());
      onDone();
    }
  }
  return (
    <form onSubmit={submit} className="form">
      <label>Loan number<input required value={loanNumber} onChange={(e) => setLoanNumber(e.target.value)} placeholder="LN26-0000001" /></label>
      <label>M-Pesa number (optional)<input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="blank = customer's registered number" /></label>
      <p className="muted wide">Payouts go only to the customer's registered M-Pesa number.</p>
      <ProblemBox problem={error} />
      {created && (
        <p className="ok" role="status">
          Requested: <Money amount={created.amount} /> to be paid out (fee <Money amount={created.fee} />). A colleague must approve before anything is sent.
        </p>
      )}
      <div className="actions"><button type="submit" disabled={busy}>Request disbursement</button></div>
    </form>
  );
}

function DisbursementRows({ rows, decide }: { rows: View[]; decide?: (d: View, kind: "approve" | "reject") => void }) {
  const { session } = useAuth();
  return (
    <table>
      <thead><tr><th>Loan</th><th className="num">Amount</th><th>To</th><th>Status</th><th>Requested</th><th /></tr></thead>
      <tbody>
        {rows.map((d) => (
          <tr key={d.disbursementId}>
            <td>{d.loanNumber}</td>
            <td className="num"><Money amount={d.amount} currency={d.currency} /></td>
            <td>{d.msisdnMasked}</td>
            <td><Status value={d.status} /> <Status value={d.attention} /></td>
            <td>{d.requestedBy} - {when(d.requestedAt)}</td>
            <td>
              {decide && (session && d.requestedBy === actorOf(session) ? <OwnItemNote /> : (
                <span className="row-actions">
                  <button type="button" onClick={() => decide(d, "approve")}>Approve and send</button>
                  <button type="button" className="secondary" onClick={() => decide(d, "reject")}>Reject</button>
                </span>
              ))}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function DisbursementsPage() {
  const api = useApi();
  const { session } = useAuth();
  const pending = useLoad<View[]>(BASE);
  const canSeeAttention = hasAnyRole(session, "OPERATIONS", "SUPERVISOR");
  const attention = useLoad<View[]>(canSeeAttention ? `${BASE}/attention` : null);
  const action = useAction();
  const canDecide = hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR");

  async function decide(d: View, kind: "approve" | "reject") {
    const verb = kind === "approve" ? "send" : "reject";
    if (!window.confirm(`${verb === "send" ? "Approve and send" : "Reject"} KES ${d.amount} for ${d.loanNumber}?`)) return;
    const done = await action.run(() => api.post<View>(`${BASE}/${d.disbursementId}/${kind}`));
    if (done) pending.reload();
  }

  return (
    <>
      <h1>Loan disbursements (M-Pesa B2C)</h1>
      {hasAnyRole(session, "LOAN_OFFICER", "OPERATIONS") && (
        <Section title="Request a disbursement"><RequestForm onDone={pending.reload} /></Section>
      )}
      <Section title="Awaiting approval">
        <ProblemBox problem={pending.error ?? action.error} />
        {pending.loading ? <Loading /> : !pending.data?.length ? <Empty>No disbursements are waiting.</Empty> : (
          <DisbursementRows rows={pending.data} decide={canDecide ? (d, k) => void decide(d, k) : undefined} />
        )}
      </Section>
      {canSeeAttention && (
        <Section title="Needing attention">
          <p className="muted">Outcome unknown or money left without a matching posting. Settle unknown outcomes on the M-Pesa close-out page; mismatches go to finance.</p>
          <ProblemBox problem={attention.error} />
          {attention.loading ? <Loading /> : !attention.data?.length ? <Empty>Nothing needs attention.</Empty> : (
            <DisbursementRows rows={attention.data} />
          )}
        </Section>
      )}
    </>
  );
}
