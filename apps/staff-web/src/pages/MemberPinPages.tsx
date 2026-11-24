import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type PinStatus = Schemas["PinStatus"];
type Setup = Schemas["SetupView"];

const STATUS_TEXT: Record<string, string> = {
  NONE: "Not set up",
  SETUP_OPEN: "Waiting for the member to choose a PIN",
  SETUP_EXPIRED: "PIN setup expired - ask for a new one",
  ACTIVE: "Active",
  LOCKED: "Locked after too many wrong attempts",
};

const EVENT_TEXT: Record<string, string> = {
  NOT_REGISTERED: "Phone not registered",
  NOT_ACTIVE: "Refused: membership not active",
  NO_PIN: "Refused: no PIN set up",
  PIN_LOCKED: "PIN locked",
  PIN_OK: "Signed in",
  PIN_WRONG: "Wrong PIN",
  ID_WRONG: "Wrong ID number during setup",
  SETUP_EXPIRED: "Setup window expired",
  PIN_SET: "PIN chosen",
  PIN_CHANGED: "PIN changed",
  BALANCES: "Viewed balances",
  STATEMENT: "Viewed mini statement",
  LOANS: "Viewed loans",
  PAYMENT_PROMPT: "M-Pesa payment prompt",
  PAYMENT_REFUSED: "Payment refused",
  SESSION_EXPIRED: "Session expired",
};

function SetupDecision({ setup, onDone }: { setup: Setup; onDone: (s: Setup) => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (!hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR")) return null;
  if (session && setup.requestedBy === actorOf(session)) return <OwnItemNote />;

  async function decide(kind: "approve" | "reject") {
    const s = await action.run(() => api.post<Setup>(`/api/v1/member-pin-setups/${setup.setupId}/${kind}`,
      { reason: note.trim() || undefined }));
    if (s) onDone(s);
  }

  return (
    <span className="row-actions">
      <input aria-label={`Note for PIN setup of ${setup.customerNumber}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve PIN setup</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

/** Mobile banking (USSD) PIN of one member: status, setup requests and recent channel activity (ADR-0034). */
export function MemberPinPanel({ customerNumber, active }: { customerNumber: string; active: boolean }) {
  const api = useApi();
  const { session } = useAuth();
  const pin = useLoad<PinStatus>(`/api/v1/customers/${encodeURIComponent(customerNumber)}/member-pin`);
  const action = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const canRequest = hasAnyRole(session, "TELLER", "BRANCH_MANAGER", "OPERATIONS");
  const p = pin.data;

  async function request(e: FormEvent) {
    e.preventDefault();
    const s = await action.run(() => api.post<Setup>(`/api/v1/customers/${encodeURIComponent(customerNumber)}/member-pin/setups`,
      { idempotencyKey: key, reason: reason.trim() }));
    if (s) {
      setNotice(`PIN ${s.kind === "RESET" ? "reset" : "setup"} requested; a branch manager or supervisor must approve it.`);
      setKey(newIdempotencyKey());
      setReason("");
      pin.reload();
    }
  }

  function decided(s: Setup) {
    setNotice(s.status === "APPROVED"
      ? "Approved. The member can now dial the USSD code from their registered phone, confirm their ID number and choose a PIN."
      : "PIN setup rejected.");
    pin.reload();
  }

  if (pin.loading && !p) return <Loading />;
  const status = p?.status ?? "NONE";
  return (
    <Section title="Mobile banking (USSD)" actions={<Status value={status} />}>
      <ProblemBox problem={pin.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {p && (
        <dl className="facts">
          <div><dt>PIN</dt><dd>{STATUS_TEXT[status] ?? status}</dd></div>
          {status === "SETUP_OPEN" && <div><dt>Setup open until</dt><dd>{when(p.setupExpiresAt)}</dd></div>}
          {p.pinSetAt && <div><dt>PIN last set</dt><dd>{when(p.pinSetAt)}</dd></div>}
          {status !== "NONE" && <div><dt>Wrong attempts</dt><dd>{p.failedAttempts} of {p.maxAttempts}</dd></div>}
          {p.lockedAt && <div><dt>Locked</dt><dd>{when(p.lockedAt)}</dd></div>}
        </dl>
      )}
      {p?.pendingSetup ? (
        <div className="decision">
          <p>{p.pendingSetup.kind === "RESET" ? "PIN reset" : "PIN setup"} requested by {p.pendingSetup.requestedBy} -
            {" "}{when(p.pendingSetup.requestedAt)}: {p.pendingSetup.reason}</p>
          <SetupDecision setup={p.pendingSetup} onDone={decided} />
        </div>
      ) : canRequest && active && status !== "SETUP_OPEN" && (
        <form className="form" onSubmit={(e) => void request(e)}>
          <label className="wide">How the member was identified (ID sighted, in person...)
            <input value={reason} onChange={(e) => setReason(e.target.value)} minLength={10} maxLength={300} required />
          </label>
          <div className="actions">
            <button type="submit" disabled={action.busy || reason.trim().length < 10}>
              {status === "NONE" ? "Request PIN setup" : "Request PIN reset"}
            </button>
          </div>
        </form>
      )}
      {!active && <p className="muted small">Mobile banking is available only to active (verified) members.</p>}
      <p className="muted small">Staff never see or choose the PIN. After approval the member dials the USSD code from their
        registered phone, confirms their ID number and chooses a PIN.</p>
      {p && p.recentEvents && p.recentEvents.length > 0 && (
        <table>
          <caption>Recent mobile banking activity</caption>
          <thead><tr><th>When</th><th>What</th><th>Detail</th></tr></thead>
          <tbody>{p.recentEvents.map((e, i) => (
            <tr key={i}><td>{when(e.occurredAt)}</td><td>{EVENT_TEXT[e.event ?? ""] ?? e.event}</td><td>{e.detail}</td></tr>
          ))}</tbody>
        </table>
      )}
    </Section>
  );
}

/** PIN setups and resets waiting for a second person (branch managers and supervisors). */
export function PinRequestsPage() {
  const pending = useLoad<Setup[]>("/api/v1/member-pin-setups");
  const [notice, setNotice] = useState<string | null>(null);

  function decided(s: Setup) {
    setNotice(s.status === "APPROVED"
      ? `Approved for ${s.customerName}. They can now choose a PIN on USSD from their registered phone.`
      : `PIN setup for ${s.customerName} rejected.`);
    pending.reload();
  }

  return (
    <>
      <h1>Mobile banking PIN requests</h1>
      <ProblemBox problem={pending.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <Section title="Waiting for approval">
        {pending.loading && !pending.data ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <table>
            <thead><tr><th>Member</th><th>Kind</th><th>Reason</th><th>Requested by</th><th /></tr></thead>
            <tbody>{pending.data.map((s) => (
              <tr key={s.setupId}>
                <td><Link to={`/customers/${s.customerNumber}`}>{s.customerName}</Link><div className="muted small">{s.customerNumber}</div></td>
                <td>{s.kind === "RESET" ? "Reset" : "First PIN"}</td><td>{s.reason}</td>
                <td>{s.requestedBy} - {when(s.requestedAt)}</td>
                <td><SetupDecision setup={s} onDone={decided} /></td>
              </tr>
            ))}</tbody>
          </table>
        )}
        <p className="muted small">Approval opens a window (24 hours by default) in which the member chooses a PIN on USSD.
          Any earlier PIN stops working at once.</p>
      </Section>
    </>
  );
}
