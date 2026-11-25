import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type FloatAccount = Schemas["FloatAccountView"];
type Check = Schemas["BalanceCheckView"];
type Settings = Schemas["FloatSettings"];

const BASE = "/api/v1/payments/mpesa/b2c/float";

/** What each alert means and what to do about it (ADR-0039). */
const ALERTS: Record<string, string> = {
  APPROVED_EXCEEDS_FLOAT: "Approved disbursements not yet paid add up to more than the float. Top up the B2C account before they are sent.",
  LOW_FLOAT: "The float is below the threshold finance set.",
  LEDGER_DIFFERENCE: "The float differs from the clearing account in the ledger. Book top-ups and charges, or check the statement reconciliation.",
  BALANCE_OUT_OF_DATE: "The last balance from M-Pesa is more than a day old.",
  LAST_CHECK_FAILED: "The last balance check failed or got no answer.",
  NO_BALANCE_YET: "No balance has been received from M-Pesa yet.",
};

function SettingsForm({ account, onSaved }: { account: FloatAccount; onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const s = account.settings;
  const [name, setName] = useState(s?.floatAccountName ?? "Utility Account");
  const [threshold, setThreshold] = useState(s?.lowFloatThreshold ?? "0.00");
  const [hour, setHour] = useState(String(s?.dailyCheckHour ?? 7));
  async function save(e: FormEvent) {
    e.preventDefault();
    const saved = await action.run(() => api.put<Settings>(`${BASE}/${account.shortCode}/settings`, {
      floatAccountName: name.trim(), lowFloatThreshold: threshold.trim(), dailyCheckHour: Number(hour),
    }));
    if (saved) onSaved();
  }
  return (
    <form className="form" onSubmit={(e) => void save(e)}>
      <ProblemBox problem={action.error} />
      <label>M-Pesa sub-account holding the float<input value={name} required onChange={(e) => setName(e.target.value)} /></label>
      <label>Alert when the float is below<input value={threshold} required inputMode="decimal" onChange={(e) => setThreshold(e.target.value)} /></label>
      <label>Daily check at (hour, 0-23)<input type="number" min={0} max={23} value={hour} required onChange={(e) => setHour(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Save float settings</button></div>
      {s?.updatedBy && <p className="muted small">Last changed by {s.updatedBy} {when(s.updatedAt)}</p>}
    </form>
  );
}

function AccountCard({ account, canCheck, canSet, onChange }: { account: FloatAccount; canCheck: boolean; canSet: boolean;
  onChange: () => void }) {
  const api = useApi();
  const action = useAction();
  const [notice, setNotice] = useState<string | null>(null);
  const l = account.latest;
  const waiting = (account.recent ?? []).some((c) => c.status === "PENDING" || c.status === "SENT");
  async function checkNow() {
    const c = await action.run(() => api.post<Check>(`${BASE}/${account.shortCode}/checks`));
    if (c) {
      setNotice("Balance check sent to M-Pesa. The answer usually arrives within a minute.");
      onChange();
    }
  }
  return (
    <Section title={`B2C ${account.shortCode}`} actions={canCheck
      ? <button type="button" disabled={action.busy || waiting} onClick={() => void checkNow()}>Check balance now</button> : undefined}>
      <ProblemBox problem={action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {(account.alerts ?? []).map((a) => <p key={a} className="warning" role="alert">{ALERTS[a] ?? a}</p>)}
      <dl className="facts">
        <div><dt>Float available ({l?.floatAccount ?? account.settings?.floatAccountName})</dt>
          <dd>{l ? formatAmount(l.floatAvailable ?? "0") : "-"}</dd></div>
        <div><dt>Clearing account {account.clearingAccount} (ledger)</dt><dd>{l ? formatAmount(l.ledgerBalance ?? "0") : "-"}</dd></div>
        <div><dt>Difference</dt><dd className={l && l.difference !== "0.00" ? "warning" : ""}>{l ? formatAmount(l.difference ?? "0") : "-"}</dd></div>
        <div><dt>Approved, not yet paid</dt><dd>{formatAmount(account.committedNow ?? "0")}</dd></div>
        <div><dt>Alert threshold</dt><dd>{formatAmount(account.settings?.lowFloatThreshold ?? "0")}</dd></div>
        <div><dt>Balance as at</dt><dd>{l ? when(l.answeredAt) : "-"}</dd></div>
      </dl>
      {l && (l.accounts?.length ?? 0) > 0 && (
        <table>
          <thead><tr><th>M-Pesa sub-account</th><th className="num">Current</th><th className="num">Available</th></tr></thead>
          <tbody>{l.accounts!.map((a) => (
            <tr key={a.name}><td>{a.name}</td><td className="num">{formatAmount(a.current ?? "0")}</td>
              <td className="num">{formatAmount(a.available ?? "0")}</td></tr>
          ))}</tbody>
        </table>
      )}
      <h3>Recent checks</h3>
      {!account.recent?.length ? <Empty>No checks yet.</Empty> : (
        <table>
          <thead><tr><th>Asked</th><th>Why</th><th>By</th><th>Status</th><th>Result</th></tr></thead>
          <tbody>{account.recent.map((c) => (
            <tr key={c.checkId}>
              <td>{when(c.requestedAt)}</td><td>{c.reason === "DAILY" ? "Daily" : "On demand"}</td><td>{c.requestedBy}</td>
              <td><Status value={c.status} /></td>
              <td>{c.resultCode === "0" ? `Available ${formatAmount(c.floatAvailable ?? "0")}` : [c.resultCode, c.resultDesc].filter(Boolean).join(" ")}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {canSet && <><h3>Settings</h3><SettingsForm account={account} onSaved={onChange} /></>}
    </Section>
  );
}

export function B2cFloatPage() {
  const { session } = useAuth();
  const accounts = useLoad<FloatAccount[]>(BASE);
  return (
    <>
      <h1>M-Pesa B2C float</h1>
      <p className="muted">The balance M-Pesa holds for loan payouts, asked every day and on demand, compared with the ledger and with the
        disbursements already approved.</p>
      <ProblemBox problem={accounts.error} />
      {accounts.loading && !accounts.data ? <Loading /> : !accounts.data?.length ? <Empty>No B2C account is set up.</Empty>
        : accounts.data.map((a) => (
          <AccountCard key={a.shortCode} account={a} onChange={() => accounts.reload()}
            canCheck={hasAnyRole(session, "OPERATIONS", "FINANCE", "SUPERVISOR")} canSet={hasAnyRole(session, "FINANCE")} />
        ))}
    </>
  );
}
