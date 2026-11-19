import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { formatMoney, isAmount } from "../money";

type Account = Schemas["SavingsAccountView"];
type Txn = Schemas["TellerTransactionView"];
const TXNS = "/api/v1/savings/teller/transactions";

/** Savings accounts of one customer, with "open account" for front-office roles. */
export function CustomerAccounts({ customerNumber, branchCode, active }: { customerNumber: string; branchCode: string; active: boolean }) {
  const api = useApi();
  const navigate = useNavigate();
  const { session } = useAuth();
  const accounts = useLoad<Account[]>(`/api/v1/savings/accounts?customerNumber=${encodeURIComponent(customerNumber)}`);
  const action = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [productCode, setProductCode] = useState("SAV01");
  const canOpen = active && hasAnyRole(session, "TELLER", "LOAN_OFFICER", "OPERATIONS");

  async function open(e: FormEvent) {
    e.preventDefault();
    const r = await action.run(() => api.post<Schemas["SavingsAccountOpened"]>("/api/v1/savings/accounts", {
      idempotencyKey: key, customerNumber, productCode: productCode.trim(), branchCode,
    }));
    if (r) {
      setKey(newIdempotencyKey());
      navigate(`/accounts/${r.accountNumber}`);
    }
  }

  return (
    <Section title="Savings accounts">
      <ProblemBox problem={accounts.error ?? action.error} />
      {accounts.loading ? <Loading /> : !accounts.data?.length ? <Empty>No savings account yet.</Empty> : (
        <table>
          <thead><tr><th>Account</th><th>Product</th><th>Status</th><th className="num">Available</th></tr></thead>
          <tbody>
            {accounts.data.map((a) => (
              <tr key={a.accountNumber}>
                <td><Link to={`/accounts/${a.accountNumber}`}>{a.accountNumber}</Link></td>
                <td>{a.productName}</td>
                <td><Status value={a.status} /></td>
                <td className="num"><Money amount={a.availableBalance} currency={a.currency} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canOpen && (
        <form onSubmit={open} className="search">
          <label>Savings product<input value={productCode} onChange={(e) => setProductCode(e.target.value)} /></label>
          <button type="submit" disabled={action.busy}>Open savings account</button>
        </form>
      )}
      {!active && <p className="muted">Accounts can be opened once the customer's identity is verified.</p>}
    </Section>
  );
}

function CashForm({ account, onDone }: { account: Account; onDone: () => void }) {
  const api = useApi();
  const { busy, error, run } = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [kind, setKind] = useState<"DEPOSIT" | "WITHDRAWAL">("DEPOSIT");
  const [amount, setAmount] = useState("");
  const [narrative, setNarrative] = useState("");
  const [result, setResult] = useState<Txn | null>(null);
  const [invalid, setInvalid] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!isAmount(amount)) {
      setInvalid("Amount must be a number with at most 2 decimal places.");
      return;
    }
    setInvalid(null);
    const r = await run(() => api.post<Txn>(TXNS, {
      idempotencyKey: key, accountNumber: account.accountNumber, kind, amount: amount.trim(),
      narrative: narrative.trim() || undefined,
    }));
    if (r) {
      setResult(r);
      setKey(newIdempotencyKey());
      setAmount("");
      setNarrative("");
      onDone();
    }
  }

  return (
    <form onSubmit={submit} className="form">
      <label>Transaction
        <select value={kind} onChange={(e) => setKind(e.target.value as "DEPOSIT" | "WITHDRAWAL")}>
          <option value="DEPOSIT">Cash deposit</option>
          <option value="WITHDRAWAL">Cash withdrawal</option>
        </select>
      </label>
      <label>Amount ({account.currency})<input required inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
      <label className="wide">Narrative (optional)<input maxLength={140} value={narrative} onChange={(e) => setNarrative(e.target.value)} /></label>
      {invalid && <div className="problem" role="alert">{invalid}</div>}
      <ProblemBox problem={error} />
      {result && (
        <p className="ok" role="status">
          {result.status === "POSTED"
            ? `Posted: ${formatMoney(result.amount, result.currency)} ${result.kind === "DEPOSIT" ? "deposited" : "withdrawn"}.`
            : `${formatMoney(result.amount, result.currency)} is above the cash limit and is waiting for a supervisor. Do not pay out or accept the cash until it is approved.`}
        </p>
      )}
      <div className="actions"><button type="submit" disabled={busy}>Post</button></div>
    </form>
  );
}

export function AccountPage() {
  const { number } = useParams();
  const { session } = useAuth();
  const account = useLoad<Account>(`/api/v1/savings/accounts/${encodeURIComponent(number ?? "")}`);
  const [range, setRange] = useState({ from: "", to: "" });
  const [applied, setApplied] = useState({ from: "", to: "" });
  const query = new URLSearchParams();
  if (applied.from) query.set("from", applied.from);
  if (applied.to) query.set("to", applied.to);
  const statement = useLoad<Schemas["SavingsStatement"]>(
    `/api/v1/savings/accounts/${encodeURIComponent(number ?? "")}/statement${query.size ? `?${query}` : ""}`);
  const reloadAll = () => { account.reload(); statement.reload(); };

  if (account.loading) return <Loading />;
  const a = account.data;
  if (!a) return <ProblemBox problem={account.error} />;
  const s = statement.data;
  return (
    <>
      <h1>Account {a.accountNumber}</h1>
      <Section title={a.customerName ?? a.customerNumber ?? ""} actions={<Status value={a.status} />}>
        <dl className="facts">
          <div><dt>Customer</dt><dd><Link to={`/customers/${a.customerNumber}`}>{a.customerNumber}</Link></dd></div>
          <div><dt>Product</dt><dd>{a.productName}</dd></div>
          <div><dt>Branch</dt><dd>{a.branchCode}</dd></div>
          <div><dt>Opened</dt><dd>{a.openedOn}</dd></div>
          <div><dt>Ledger balance</dt><dd><Money amount={a.ledgerBalance} currency={a.currency} /></dd></div>
          <div><dt>Held</dt><dd><Money amount={a.heldAmount} currency={a.currency} /></dd></div>
          <div><dt>Available</dt><dd><strong><Money amount={a.availableBalance} currency={a.currency} /></strong></dd></div>
        </dl>
      </Section>
      {hasAnyRole(session, "TELLER") && <Section title="Teller cash"><CashForm account={a} onDone={reloadAll} /></Section>}
      <Section title="Statement">
        <form className="search" onSubmit={(e) => { e.preventDefault(); setApplied(range); }}>
          <label>From<input type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /></label>
          <label>To<input type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></label>
          <button type="submit">Show</button>
        </form>
        <ProblemBox problem={statement.error} />
        {statement.loading ? <Loading /> : s && (
          <>
            <p className="muted">{s.from} to {s.to} - opening <Money amount={s.openingBalance} currency={s.currency} />, closing <Money amount={s.closingBalance} currency={s.currency} /></p>
            {!s.lines?.length ? <Empty>No transactions in this period.</Empty> : (
              <table className="compact">
                <thead><tr><th>Date</th><th>Description</th><th className="num">Debit</th><th className="num">Credit</th><th className="num">Balance</th></tr></thead>
                <tbody>
                  {s.lines.map((l, i) => (
                    <tr key={`${l.entryId}-${i}`}>
                      <td>{l.date}</td><td>{l.description}</td>
                      <td className="num"><Money amount={l.debit} /></td>
                      <td className="num"><Money amount={l.credit} /></td>
                      <td className="num"><Money amount={l.balance} /></td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><th colSpan={2}>Totals</th><th className="num"><Money amount={s.totalDebits} /></th><th className="num"><Money amount={s.totalCredits} /></th><th /></tr></tfoot>
              </table>
            )}
          </>
        )}
      </Section>
    </>
  );
}

/** Teller desk: find an account; approve large cash transactions (supervisors). */
export function TellerPage() {
  const api = useApi();
  const navigate = useNavigate();
  const { session } = useAuth();
  const [accountNumber, setAccountNumber] = useState("");
  const pending = useLoad<Txn[]>(TXNS);
  const action = useAction();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const canDecide = hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR");

  async function decide(t: Txn, kind: "approve" | "reject") {
    const done = await action.run(() => api.post(`${TXNS}/${t.txnId}/${kind}`, { reason: reasons[t.txnId ?? ""]?.trim() || undefined }));
    if (done) pending.reload();
  }

  return (
    <>
      <h1>Teller</h1>
      {hasAnyRole(session, "TELLER") && (
        <form className="search" onSubmit={(e) => { e.preventDefault(); if (accountNumber.trim()) navigate(`/accounts/${accountNumber.trim()}`); }}>
          <label>Account number<input value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} placeholder="2000000014" /></label>
          <button type="submit">Open account</button>
        </form>
      )}
      <Section title="Cash transactions awaiting approval">
        <ProblemBox problem={pending.error ?? action.error} />
        {pending.loading ? <Loading /> : !pending.data?.length ? <Empty>Nothing is waiting.</Empty> : (
          <table>
            <thead><tr><th>Account</th><th>Type</th><th className="num">Amount</th><th>Captured by</th><th /></tr></thead>
            <tbody>
              {pending.data.map((t) => (
                <tr key={t.txnId}>
                  <td><Link to={`/accounts/${t.accountNumber}`}>{t.accountNumber}</Link></td>
                  <td>{t.kind === "DEPOSIT" ? "Deposit" : "Withdrawal"} - {t.narrative}</td>
                  <td className="num"><Money amount={t.amount} currency={t.currency} /></td>
                  <td>{t.requestedBy} - {when(t.requestedAt)}</td>
                  <td>{canDecide && (session && t.requestedBy === actorOf(session) ? <OwnItemNote /> : (
                    <span className="row-actions">
                      <input aria-label={`Reason for ${t.accountNumber}`} placeholder="reason (to reject)"
                        value={reasons[t.txnId ?? ""] ?? ""} onChange={(e) => setReasons({ ...reasons, [t.txnId ?? ""]: e.target.value })} />
                      <button type="button" disabled={action.busy} onClick={() => void decide(t, "approve")}>Approve</button>
                      <button type="button" className="secondary" disabled={action.busy || (reasons[t.txnId ?? ""] ?? "").trim().length < 3}
                        onClick={() => void decide(t, "reject")}>Reject</button>
                    </span>
                  ))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}
