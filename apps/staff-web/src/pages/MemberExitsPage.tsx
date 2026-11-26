import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Settings = Schemas["ExitSettingsView"];
type Position = Schemas["MemberExitPosition"];
type Exit = Schemas["ExitView"];

const BASE = "/api/v1/member-exits";
const METHOD: Record<string, string> = { BANK_TRANSFER: "Bank transfer", CHEQUE: "Cheque", CASH: "Cash", MPESA: "M-Pesa" };

function Blockers({ items }: { items: string[] | undefined }) {
  if (!items?.length) return <p className="ok">Nothing outstanding.</p>;
  return (
    <div className="warning" role="alert">
      <p>Before this member can leave:</p>
      <ul>{items.map((b) => <li key={b}>{b}</li>)}</ul>
    </div>
  );
}

function RequestForm({ customerNumber, onDone }: { customerNumber: string; onDone: (e: Exit) => void }) {
  const api = useApi();
  const action = useAction();
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(ev: FormEvent) {
    ev.preventDefault();
    const e = await action.run(() => api.post<Exit>(BASE, { idempotencyKey: key, customerNumber, reason: reason.trim() }));
    if (e) {
      setKey(newIdempotencyKey());
      setReason("");
      onDone(e);
    }
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Ask to leave the Sacco</h3>
      <ProblemBox problem={action.error} />
      <label>Reason the member gives (at least 10 characters)<input value={reason} required minLength={10} maxLength={300}
        onChange={(e) => setReason(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Ask to leave</button></div>
      <p className="muted small">The notice period starts today. A branch manager or supervisor approves the exit once it has passed.</p>
    </form>
  );
}

function ExitActions({ exit, onDone }: { exit: Exit; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  const own = session ? exit.requestedBy === actorOf(session) : false;
  const canDecide = hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR");
  const canCancel = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS", "SUPERVISOR");
  async function act(kind: "approve" | "reject" | "cancel") {
    const body = kind === "approve" ? undefined : { reason: note.trim() };
    if (await action.run(() => api.post<Exit>(`${BASE}/${exit.exitId}/${kind}`, body))) onDone();
  }
  return (
    <span className="row-actions">
      {(canDecide || canCancel) && <input aria-label={`Note for the exit of ${exit.customerNumber}`} placeholder="note (needed to reject or cancel)"
        value={note} onChange={(e) => setNote(e.target.value)} />}
      {canDecide && !own && <button type="button" disabled={action.busy || (exit.blockers?.length ?? 0) > 0}
        onClick={() => void act("approve")}>Approve exit</button>}
      {canDecide && !own && <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3}
        onClick={() => void act("reject")}>Reject</button>}
      {canDecide && own && <OwnItemNote />}
      {canCancel && <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3}
        onClick={() => void act("cancel")}>Cancel request</button>}
      <ProblemBox problem={action.error} />
    </span>
  );
}

function RefundForm({ exit, onDone }: { exit: Exit; onDone: () => void }) {
  const api = useApi();
  const action = useAction();
  const [method, setMethod] = useState("BANK_TRANSFER");
  const [paidFrom, setPaidFrom] = useState("");
  const [reference, setReference] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.post<Exit>(`${BASE}/${exit.exitId}/refund-payments`,
      { idempotencyKey: key, method, paidFromAccount: paidFrom.trim(), reference: reference.trim() }))) {
      setKey(newIdempotencyKey());
      onDone();
    }
  }
  return (
    <form className="form inline" onSubmit={(e) => void submit(e)}>
      <ProblemBox problem={action.error} />
      <label>Paid by<select value={method} onChange={(e) => setMethod(e.target.value)}>
        {Object.entries(METHOD).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label>Paid from account<input value={paidFrom} required placeholder="bank or cash account" onChange={(e) => setPaidFrom(e.target.value)} /></label>
      <label>Payment reference<input value={reference} required minLength={3} onChange={(e) => setReference(e.target.value)} /></label>
      <button type="submit" disabled={action.busy}>Record refund paid</button>
    </form>
  );
}

function RefundDecision({ exit, onDone }: { exit: Exit; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  const p = exit.payment;
  if (!p) return null;
  if (session && p.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    const body = kind === "approve" ? undefined : { reason: note.trim() };
    if (await action.run(() => api.post<Exit>(`${BASE}/refund-payments/${p?.paymentId}/${kind}`, body))) onDone();
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for the refund to ${exit.customerNumber}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve payment</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

function ExitsSection({ isFinance, canSettleRefunds }: { isFinance: boolean; canSettleRefunds: boolean }) {
  const exits = useLoad<Exit[]>(BASE);
  const reload = () => exits.reload();
  return (
    <Section title="Exit requests">
      <ProblemBox problem={exits.error} />
      {exits.loading && !exits.data ? <Loading /> : !exits.data?.length ? <Empty>No member has asked to leave.</Empty> : (
        <table>
          <thead><tr><th>Member</th><th>Reason</th><th>Notice ends</th><th>Status</th><th className="num">Refund</th><th>Asked</th><th /></tr></thead>
          <tbody>{exits.data.map((e) => {
            const owed = e.status === "APPROVED" && Number(e.refundTotal ?? 0) > 0;
            const paid = e.payment?.status === "APPROVED";
            return (
              <tr key={e.exitId}>
                <td>{e.customerNumber}<div className="muted small">{e.memberName}</div></td>
                <td>{e.reason}{e.decisionReason && <div className="muted small">Decision: {e.decisionReason}</div>}
                  {e.status === "REQUESTED" && (e.blockers?.length ?? 0) > 0 && <div className="warning small">{e.blockers?.join("; ")}</div>}</td>
                <td>{e.earliestExitDate}</td>
                <td><Status value={e.status} />{e.decidedBy && <div className="muted small">{e.decidedBy}</div>}
                  {owed && <div className="muted small">{paid ? `Refund paid (${METHOD[e.payment?.method ?? ""]}, ${e.payment?.reference})`
                    : e.payment?.status === "REQUESTED" ? "Refund payment waiting for approval" : "Refund not yet paid"}</div>}</td>
                <td className="num">{e.refundTotal ? formatAmount(e.refundTotal) : ""}</td>
                <td>{e.requestedBy}<div className="muted small">{when(e.requestedAt)}</div></td>
                <td>
                  {e.status === "REQUESTED" && <ExitActions exit={e} onDone={reload} />}
                  {owed && !paid && e.payment?.status !== "REQUESTED" && isFinance && <RefundForm exit={e} onDone={reload} />}
                  {owed && e.payment?.status === "REQUESTED" && canSettleRefunds && <RefundDecision exit={e} onDone={reload} />}
                </td>
              </tr>
            );
          })}</tbody>
        </table>
      )}
    </Section>
  );
}

function SettingsForm({ settings, onSaved }: { settings: Settings | null; onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const [notice, setNotice] = useState(String(settings?.noticeDays ?? 60));
  const [account, setAccount] = useState(settings?.refundsPayableAccount ?? "");
  async function save(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.put<Settings>(`${BASE}/settings`, { noticeDays: Number(notice), refundsPayableAccount: account.trim() }))) onSaved();
  }
  return (
    <Section title="Exit settings">
      <form className="form" onSubmit={(e) => void save(e)}>
        <ProblemBox problem={action.error} />
        <label>Notice period (days)<input type="number" min={0} max={365} value={notice} required onChange={(e) => setNotice(e.target.value)} /></label>
        <label>Member refunds payable (liability)<input value={account} required onChange={(e) => setAccount(e.target.value)} /></label>
        <div className="actions"><button type="submit" disabled={action.busy}>Save exit settings</button></div>
        {settings?.updatedBy && <p className="muted small">Last changed by {settings.updatedBy} {when(settings.updatedAt)}</p>}
        <p className="muted small">The notice period is a by-law decision; it applies to requests made from now on.</p>
      </form>
    </Section>
  );
}

/** Members leaving the Sacco: position, request, approval, refund payment and settings (ADR-0042). */
export function MemberExitsPage() {
  const api = useApi();
  const { session } = useAuth();
  const settings = useLoad<Settings>(`${BASE}/settings`);
  const lookup = useAction();
  const [number, setNumber] = useState("");
  const [position, setPosition] = useState<Position | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const canAsk = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS");
  const isFinance = hasAnyRole(session, "FINANCE");

  async function load(customerNumber: string) {
    const p = await lookup.run(() => api.get<Position>(`${BASE}/members/${encodeURIComponent(customerNumber)}`));
    setPosition(p ?? null);
  }
  async function find(e: FormEvent) {
    e.preventDefault();
    setNotice(null);
    await load(number.trim().toUpperCase());
  }

  const current = position && (position.memberStatus === "ACTIVE" || position.memberStatus === "BLOCKED");
  return (
    <>
      <h1>Members leaving</h1>
      {notice && <p className="ok" role="status">{notice}</p>}
      {settings.loading && !settings.data && !settings.error ? <Loading /> : !settings.data &&
        <p className="warning">Member exits are not set up yet; finance sets the notice period and refunds account below.</p>}
      <Section title="Find a member">
        <form className="form" onSubmit={(e) => void find(e)}>
          <label>Member number<input value={number} required placeholder="CU26-0000001" onChange={(e) => setNumber(e.target.value)} /></label>
          <div className="actions"><button type="submit" disabled={lookup.busy}>Show position</button></div>
        </form>
        <ProblemBox problem={lookup.error} />
        {position && (
          <>
            <dl className="facts">
              <div><dt>Member</dt><dd>{position.customerNumber} - {position.memberName}</dd></div>
              <div><dt>Status</dt><dd><Status value={position.memberStatus} /></dd></div>
              {current && <div><dt>Refund if leaving today</dt><dd>{formatAmount(position.refundToday)}</dd></div>}
            </dl>
            {current && <Blockers items={position.blockers} />}
            {current && !!position.accounts?.length && (
              <table>
                <thead><tr><th>Savings account</th><th className="num">Balance</th><th className="num">Interest not yet credited</th>
                  <th className="num">Tax</th><th className="num">Refund</th></tr></thead>
                <tbody>{position.accounts.map((a) => (
                  <tr key={a.accountNumber}><td>{a.accountNumber}</td><td className="num">{formatAmount(a.balance)}</td>
                    <td className="num">{formatAmount(a.interestGross)}</td><td className="num">{formatAmount(a.interestTax)}</td>
                    <td className="num">{formatAmount(a.refund)}</td></tr>
                ))}</tbody>
              </table>
            )}
            {position.openExit && <p className="muted">Asked to leave on {position.openExit.requestedOn}; the notice period ends
              {" "}{position.openExit.earliestExitDate}. Status: {position.openExit.status}.</p>}
            {current && !position.openExit && canAsk && settings.data && <RequestForm customerNumber={position.customerNumber ?? ""}
              onDone={(e) => {
                setNotice(`${e.customerNumber} asked to leave; the notice period ends ${e.earliestExitDate}.`);
                setTick((n) => n + 1);
                void load(position.customerNumber ?? "");
              }} />}
          </>
        )}
      </Section>
      <ExitsSection key={tick} isFinance={isFinance} canSettleRefunds={hasAnyRole(session, "FINANCE", "SUPERVISOR")} />
      {isFinance && <SettingsForm key={settings.data?.updatedAt ?? "new"} settings={settings.data} onSaved={() => settings.reload()} />}
    </>
  );
}
