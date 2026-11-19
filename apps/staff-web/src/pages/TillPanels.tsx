import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { ApiError, newIdempotencyKey, type Problem, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { toProblem, useAction, useLoad } from "../hooks";
import { isAmount } from "../money";

type TillSession = Schemas["TillSessionView"];
type Till = Schemas["TillView"];
type Transfer = Schemas["CashTransferView"];
const BASE = "/api/v1/savings";

/** The teller's own till for the day: open it, ask the vault for cash or return it, count and close. */
export function MyTill({ onChange }: { onChange?: () => void }) {
  const api = useApi();
  const [mine, setMine] = useState<TillSession | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<Problem | null>(null);
  const [tick, setTick] = useState(0);
  const tills = useLoad<Till[]>(mine || !loaded ? null : `${BASE}/tills`);
  const action = useAction();
  const [tillCode, setTillCode] = useState("");
  const [direction, setDirection] = useState<"VAULT_TO_TILL" | "TILL_TO_VAULT">("VAULT_TO_TILL");
  const [amount, setAmount] = useState("");
  const [transferKey, setTransferKey] = useState(newIdempotencyKey);
  const [counted, setCounted] = useState("");
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api.get<TillSession>(`${BASE}/till-sessions/mine`)
      .then((s) => { if (active) { setMine(s); setLoadError(null); } })
      .catch((e: unknown) => {
        if (!active) return;
        if (e instanceof ApiError && e.problem.status === 404) { setMine(null); setLoadError(null); }
        else setLoadError(toProblem(e));
      })
      .finally(() => active && setLoaded(true));
    return () => { active = false; };
  }, [api, tick]);
  const reload = useCallback(() => { setTick((t) => t + 1); onChange?.(); }, [onChange]);

  async function open(e: FormEvent) {
    e.preventDefault();
    const s = await action.run(() => api.post<TillSession>(`${BASE}/till-sessions`, { tillCode }));
    if (s) { setNotice(`Till ${s.tillCode} is open.`); reload(); }
  }

  async function transfer(e: FormEvent) {
    e.preventDefault();
    if (!mine) return;
    const t = await action.run(() => api.post<Transfer>(`${BASE}/till-sessions/${mine.sessionId}/transfers`, {
      idempotencyKey: transferKey, direction, amount,
    }));
    if (t) {
      setTransferKey(newIdempotencyKey());
      setAmount("");
      setNotice(`Transfer of ${t.amount} ${t.currency} is waiting for a supervisor.`);
      reload();
    }
  }

  async function close(e: FormEvent) {
    e.preventDefault();
    if (!mine) return;
    const s = await action.run(() => api.post<TillSession>(`${BASE}/till-sessions/${mine.sessionId}/close`, { countedAmount: counted }));
    if (s) {
      setCounted("");
      setNotice(s.status === "CLOSED" ? "Till balanced and closed." : `Count differs by ${s.variance}; a supervisor must approve it.`);
      reload();
    }
  }

  return (
    <Section title="My till">
      <ProblemBox problem={loadError ?? tills.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {!loaded ? <Loading /> : !mine ? (
        <form className="search" onSubmit={(e) => void open(e)}>
          <label>Till
            <select value={tillCode} onChange={(e) => setTillCode(e.target.value)} required>
              <option value="">Choose a till</option>
              {(tills.data ?? []).filter((t) => t.active && !t.openedBy).map((t) => (
                <option key={t.tillCode} value={t.tillCode}>{t.tillCode} ({t.branchCode})</option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={action.busy || !tillCode}>Open till</button>
        </form>
      ) : (
        <>
          <dl className="facts">
            <dt>Till</dt><dd>{mine.tillCode} - {mine.branchCode}</dd>
            <dt>Business date</dt><dd>{mine.businessDate}</dd>
            <dt>Status</dt><dd><Status value={mine.status} /></dd>
            <dt>Cash expected in till</dt><dd><Money amount={mine.expectedCash} currency={mine.currency} /></dd>
            {mine.status === "CLOSING" && (<><dt>Counted</dt><dd><Money amount={mine.countedAmount} currency={mine.currency} /></dd>
              <dt>Difference</dt><dd><Money amount={mine.variance} currency={mine.currency} /> - waiting for a supervisor</dd></>)}
            {(mine.pendingTransactions ?? 0) + (mine.pendingTransfers ?? 0) > 0 && (
              <><dt>Waiting for approval</dt><dd>{mine.pendingTransactions ?? 0} cash transaction(s), {mine.pendingTransfers ?? 0} transfer(s)</dd></>)}
          </dl>
          {mine.status === "OPEN" && (
            <>
              <form className="search" onSubmit={(e) => void transfer(e)}>
                <label>Vault transfer
                  <select value={direction} onChange={(e) => setDirection(e.target.value as typeof direction)}>
                    <option value="VAULT_TO_TILL">Cash from vault to till</option>
                    <option value="TILL_TO_VAULT">Cash from till to vault</option>
                  </select>
                </label>
                <label>Transfer amount<input value={amount} inputMode="decimal" onChange={(e) => setAmount(e.target.value)} /></label>
                <button type="submit" disabled={action.busy || !isAmount(amount)}>Request transfer</button>
              </form>
              <form className="search" onSubmit={(e) => void close(e)}>
                <label>Counted cash<input value={counted} inputMode="decimal" onChange={(e) => setCounted(e.target.value)} /></label>
                <button type="submit" disabled={action.busy || !isAmount(counted)}>Count and close till</button>
              </form>
            </>
          )}
        </>
      )}
    </Section>
  );
}

/** Supervisor queues: vault transfers to approve and till counts with a difference. */
export function TillApprovals() {
  const api = useApi();
  const { session } = useAuth();
  const transfers = useLoad<Transfer[]>(`${BASE}/cash-transfers`);
  const closing = useLoad<TillSession[]>(`${BASE}/till-sessions/closing`);
  const action = useAction();
  const [notes, setNotes] = useState<Record<string, string>>({});
  const me = session ? actorOf(session) : "";
  const note = (id: string | undefined) => (notes[id ?? ""] ?? "").trim();
  const setNote = (id: string | undefined, v: string) => setNotes({ ...notes, [id ?? ""]: v });

  async function decideTransfer(t: Transfer, kind: "approve" | "reject") {
    if (await action.run(() => api.post(`${BASE}/cash-transfers/${t.transferId}/${kind}`, { reason: note(t.transferId) || undefined }))) {
      transfers.reload();
    }
  }

  async function decideCount(s: TillSession, kind: "approve-variance" | "recount") {
    if (await action.run(() => api.post(`${BASE}/till-sessions/${s.sessionId}/${kind}`, { reason: note(s.sessionId) }))) {
      closing.reload();
    }
  }

  return (
    <>
      <Section title="Vault transfers awaiting approval">
        <ProblemBox problem={transfers.error ?? action.error} />
        {transfers.loading && !transfers.data ? <Loading /> : !transfers.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <table>
            <thead><tr><th>Till</th><th>Direction</th><th className="num">Amount</th><th>Requested by</th><th /></tr></thead>
            <tbody>
              {transfers.data.map((t) => (
                <tr key={t.transferId}>
                  <td>{t.tillCode}</td>
                  <td>{t.direction === "VAULT_TO_TILL" ? "Vault to till" : "Till to vault"}</td>
                  <td className="num"><Money amount={t.amount} currency={t.currency} /></td>
                  <td>{t.requestedBy} - {when(t.requestedAt)}</td>
                  <td>{t.requestedBy === me ? <OwnItemNote /> : (
                    <span className="row-actions">
                      <input aria-label={`Reason for transfer ${t.tillCode}`} placeholder="reason (to reject)"
                        value={notes[t.transferId ?? ""] ?? ""} onChange={(e) => setNote(t.transferId, e.target.value)} />
                      <button type="button" disabled={action.busy} onClick={() => void decideTransfer(t, "approve")}>Approve</button>
                      <button type="button" className="secondary" disabled={action.busy || note(t.transferId).length < 3}
                        onClick={() => void decideTransfer(t, "reject")}>Reject</button>
                    </span>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <Section title="Till counts with a difference">
        <ProblemBox problem={closing.error} />
        {closing.loading && !closing.data ? <Loading /> : !closing.data?.length ? <Empty>No till is waiting.</Empty> : (
          <table>
            <thead><tr><th>Till</th><th>Teller</th><th className="num">Expected</th><th className="num">Counted</th><th className="num">Difference</th><th /></tr></thead>
            <tbody>
              {closing.data.map((s) => (
                <tr key={s.sessionId}>
                  <td>{s.tillCode}</td>
                  <td>{s.teller}</td>
                  <td className="num"><Money amount={s.expectedCash} currency={s.currency} /></td>
                  <td className="num"><Money amount={s.countedAmount} currency={s.currency} /></td>
                  <td className="num"><Money amount={s.variance} currency={s.currency} /></td>
                  <td>{s.teller === me ? <OwnItemNote /> : (
                    <span className="row-actions">
                      <input aria-label={`Note for till ${s.tillCode}`} placeholder="note (required)"
                        value={notes[s.sessionId ?? ""] ?? ""} onChange={(e) => setNote(s.sessionId, e.target.value)} />
                      <button type="button" disabled={action.busy || note(s.sessionId).length < 3}
                        onClick={() => void decideCount(s, "approve-variance")}>Accept difference</button>
                      <button type="button" className="secondary" disabled={action.busy || note(s.sessionId).length < 3}
                        onClick={() => void decideCount(s, "recount")}>Send back to recount</button>
                    </span>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
