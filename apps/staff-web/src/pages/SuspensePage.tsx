import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, ProblemBox, Section } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type Item = Schemas["OpenItem"];
const BASE = "/api/v1/payments/suspense";

function ResolveForm({ item, onDone, onCancel }: { item: Item; onDone: () => void; onCancel: () => void }) {
  const api = useApi();
  const { busy, error, run } = useAction();
  const [targetType, setTargetType] = useState<"DEPOSIT_ACCOUNT" | "LOAN">("DEPOSIT_ACCOUNT");
  const [targetReference, setTargetReference] = useState("");
  const [reason, setReason] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    const done = await run(() => api.post(`${BASE}/items/${item.paymentId}/resolutions`, {
      targetType, targetReference: targetReference.trim(), reason: reason.trim(),
    }));
    if (done) onDone();
  }
  return (
    <form onSubmit={submit} className="form inset">
      <label>Move to
        <select value={targetType} onChange={(e) => setTargetType(e.target.value as "DEPOSIT_ACCOUNT" | "LOAN")}>
          <option value="DEPOSIT_ACCOUNT">Savings account</option>
          <option value="LOAN">Loan</option>
        </select>
      </label>
      <label>{targetType === "LOAN" ? "Loan number" : "Account number"}<input required value={targetReference} onChange={(e) => setTargetReference(e.target.value)} /></label>
      <label className="wide">Reason<input required value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. customer called: typo in account number" /></label>
      <ProblemBox problem={error} />
      <div className="actions">
        <button type="submit" disabled={busy}>Submit for approval</button>
        <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

export function SuspensePage() {
  const api = useApi();
  const { session } = useAuth();
  const items = useLoad<Item[]>(`${BASE}/items`);
  const action = useAction();
  const [resolving, setResolving] = useState<Item | null>(null);

  async function decide(id: string, kind: "approve" | "reject") {
    const done = await action.run(() => api.post(`${BASE}/resolutions/${id}/${kind}`));
    if (done) items.reload();
  }

  return (
    <>
      <h1>Suspense - unmatched paybill receipts</h1>
      {resolving && <Section title={`Clear receipt ${resolving.providerRef}`}>
        <ResolveForm item={resolving} onCancel={() => setResolving(null)} onDone={() => { setResolving(null); items.reload(); }} />
      </Section>}
      <Section title="Open items">
        <ProblemBox problem={items.error ?? action.error} />
        {items.loading ? <Loading /> : !items.data?.length ? <Empty>Suspense is clear.</Empty> : (
          <table>
            <thead><tr><th>Receipt</th><th className="num">Amount</th><th>Reference given</th><th>Phone</th><th className="num">Age (days)</th><th /></tr></thead>
            <tbody>
              {items.data.map((i) => (
                <tr key={i.paymentId}>
                  <td className="mono">{i.providerRef}</td>
                  <td className="num"><Money amount={i.amount} currency={i.currency} /></td>
                  <td>{i.billRef}</td>
                  <td>{i.msisdnMasked}</td>
                  <td className="num">{i.ageDays}</td>
                  <td>
                    {i.pendingResolutionId ? (
                      hasAnyRole(session, "SUPERVISOR") ? (
                        <span className="row-actions">
                          <button type="button" disabled={action.busy} onClick={() => void decide(i.pendingResolutionId!, "approve")}>Approve move</button>
                          <button type="button" className="secondary" disabled={action.busy} onClick={() => void decide(i.pendingResolutionId!, "reject")}>Reject</button>
                        </span>
                      ) : <span className="muted">Awaiting supervisor</span>
                    ) : hasAnyRole(session, "OPERATIONS") && (
                      <button type="button" onClick={() => setResolving(i)}>Clear</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
