import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { downloadCsv } from "../csv";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Settings = Schemas["ShareSettingsView"];
type Member = Schemas["MemberShares"];
type Register = Schemas["ShareRegister"];
type Transfer = Schemas["ShareTransferView"];
type Run = Schemas["DividendRunView"];
type Preview = Schemas["DividendPreview"];
type Line = Schemas["DividendLineView"];

const BASE = "/api/v1/shares";
const KIND: Record<string, string> = { PURCHASE: "Bought", TRANSFER_IN: "Transferred in", TRANSFER_OUT: "Transferred out" };

function SettingsNote({ settings }: { settings: Settings | null }) {
  if (!settings) return <p className="warning">Share capital is not set up yet. Finance sets it up on the Dividends page.</p>;
  return (
    <p className="muted">One share is {formatAmount(settings.parValue)}; every shareholder keeps at least
      {" "}{formatAmount(settings.minimumHolding)}.</p>
  );
}

function PurchaseForm({ member, onDone }: { member: Member; onDone: (m: Member) => void }) {
  const api = useApi();
  const action = useAction();
  const [account, setAccount] = useState("");
  const [amount, setAmount] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const m = await action.run(() => api.post<Member>(`${BASE}/purchases`, {
      idempotencyKey: key, customerNumber: member.customerNumber, accountNumber: account.trim(), amount: amount.trim(),
    }));
    if (m) {
      setKey(newIdempotencyKey());
      setAmount("");
      onDone(m);
    }
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Buy shares from savings</h3>
      <ProblemBox problem={action.error} />
      <label>Pay from the member's savings account<input value={account} required pattern="[0-9]{10}" inputMode="numeric"
        onChange={(e) => setAccount(e.target.value)} /></label>
      <label>Amount to buy (whole shares)<input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Buy shares</button></div>
    </form>
  );
}

function TransferForm({ member, onDone }: { member: Member; onDone: (t: Transfer) => void }) {
  const api = useApi();
  const action = useAction();
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const t = await action.run(() => api.post<Transfer>(`${BASE}/transfers`, {
      idempotencyKey: key, fromCustomerNumber: member.customerNumber, toCustomerNumber: to.trim().toUpperCase(),
      amount: amount.trim(), reason: reason.trim(),
    }));
    if (t) {
      setKey(newIdempotencyKey());
      setTo("");
      setAmount("");
      setReason("");
      onDone(t);
    }
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Transfer shares to another member</h3>
      <ProblemBox problem={action.error} />
      <label>To member number<input value={to} required placeholder="CU26-0000001" onChange={(e) => setTo(e.target.value)} /></label>
      <label>Amount to transfer (whole shares)<input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} /></label>
      <label>Reason (at least 10 characters)<input value={reason} required minLength={10} maxLength={300}
        onChange={(e) => setReason(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Ask for the transfer</button></div>
      <p className="muted small">A branch manager or supervisor approves it; nothing moves until then.</p>
    </form>
  );
}

function TransferDecision({ transfer, onDone }: { transfer: Transfer; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (session && transfer.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    if (await action.run(() => api.post<Transfer>(`${BASE}/transfers/${transfer.transferId}/${kind}`, { reason: note.trim() || undefined }))) {
      onDone();
    }
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for transfer from ${transfer.fromCustomerNumber}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

function TransfersSection({ canDecide }: { canDecide: boolean }) {
  const transfers = useLoad<Transfer[]>(`${BASE}/transfers`);
  return (
    <Section title="Share transfers">
      <ProblemBox problem={transfers.error} />
      {transfers.loading && !transfers.data ? <Loading /> : !transfers.data?.length ? <Empty>No share transfers yet.</Empty> : (
        <table>
          <thead><tr><th>From</th><th>To</th><th className="num">Amount</th><th>Reason</th><th>Status</th><th>Asked</th><th /></tr></thead>
          <tbody>{transfers.data.map((t) => (
            <tr key={t.transferId}>
              <td>{t.fromCustomerNumber}<div className="muted small">{t.fromName} - holds {formatAmount(t.sellerHolding)}</div></td>
              <td>{t.toCustomerNumber}<div className="muted small">{t.toName}</div></td>
              <td className="num">{formatAmount(t.amount)}<div className="muted small">{t.shares} shares</div></td>
              <td>{t.reason}{t.decisionReason && <div className="muted small">Decision: {t.decisionReason}</div>}</td>
              <td><Status value={t.status} />{t.decidedBy && <div className="muted small">{t.decidedBy}</div>}</td>
              <td>{t.requestedBy}<div className="muted small">{when(t.requestedAt)}</div></td>
              <td>{t.status === "REQUESTED" && canDecide && <TransferDecision transfer={t} onDone={() => transfers.reload()} />}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </Section>
  );
}

function RegisterSection() {
  const register = useLoad<Register>(`${BASE}/register`);
  const r = register.data;
  return (
    <Section title="Share register" actions={r?.holders?.length ? (
      <button type="button" className="secondary" onClick={() => downloadCsv("share-register.csv", [
        ["Member", "Name", "Branch", "Holding", "Shares"],
        ...(r.holders ?? []).map((h) => [h.customerNumber, h.name, h.branchCode, h.holding, h.shares]),
      ])}>Download register</button>) : undefined}>
      <ProblemBox problem={register.error} />
      {register.loading && !r ? <Loading /> : r && (
        <>
          <dl className="facts">
            <div><dt>Shareholders</dt><dd>{r.members}</dd></div>
            <div><dt>Share capital</dt><dd>{formatAmount(r.totalHolding)}</dd></div>
            <div><dt>Shares</dt><dd>{r.totalShares}</dd></div>
          </dl>
          {!r.holders?.length ? <Empty>No member holds shares yet.</Empty> : (
            <table>
              <thead><tr><th>Member</th><th>Name</th><th>Branch</th><th className="num">Holding</th><th className="num">Shares</th></tr></thead>
              <tbody>{r.holders.map((h) => (
                <tr key={h.customerNumber}><td>{h.customerNumber}</td><td>{h.name}</td><td>{h.branchCode}</td>
                  <td className="num">{formatAmount(h.holding)}</td><td className="num">{h.shares}</td></tr>
              ))}</tbody>
            </table>
          )}
        </>
      )}
    </Section>
  );
}

/** A member's shares, buying and transferring them, the transfers awaiting a decision and the register (ADR-0040). */
export function SharesPage() {
  const api = useApi();
  const { session } = useAuth();
  const settings = useLoad<Settings>(`${BASE}/settings`);
  const lookup = useAction();
  const [number, setNumber] = useState("");
  const [member, setMember] = useState<Member | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const canBuy = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER");
  const canAskTransfer = hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS");
  const seesTransfers = hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS", "SUPERVISOR");
  const seesRegister = hasAnyRole(session, "FINANCE", "SUPERVISOR", "BRANCH_MANAGER");
  const ready = settings.data !== null;

  async function find(e: FormEvent) {
    e.preventDefault();
    setNotice(null);
    const m = await lookup.run(() => api.get<Member>(`${BASE}/members/${encodeURIComponent(number.trim().toUpperCase())}`));
    setMember(m ?? null);
  }

  return (
    <>
      <h1>Member shares</h1>
      {settings.loading && !settings.data ? <Loading /> : <SettingsNote settings={settings.data} />}
      {notice && <p className="ok" role="status">{notice}</p>}
      {ready && (
        <Section title="Find a member">
          <form className="form" onSubmit={(e) => void find(e)}>
            <label>Member number<input value={number} required placeholder="CU26-0000001" onChange={(e) => setNumber(e.target.value)} /></label>
            <div className="actions"><button type="submit" disabled={lookup.busy}>Show shares</button></div>
          </form>
          <ProblemBox problem={lookup.error} />
          {member && (
            <>
              <dl className="facts">
                <div><dt>Member</dt><dd>{member.customerNumber} - {member.name}</dd></div>
                <div><dt>Holding</dt><dd>{formatAmount(member.holding)} ({member.shares} shares)</dd></div>
                <div><dt>Minimum holding</dt><dd>{formatAmount(member.minimumHolding)}</dd></div>
              </dl>
              {!member.transactions?.length ? <Empty>No share movements yet.</Empty> : (
                <table>
                  <thead><tr><th>Business date</th><th>Movement</th><th className="num">Amount</th><th>Paid from</th><th>By</th></tr></thead>
                  <tbody>{member.transactions.map((t) => (
                    <tr key={t.txnId}><td>{t.businessDate}</td><td>{KIND[t.kind ?? ""] ?? t.kind}</td>
                      <td className="num">{formatAmount(t.amount)}</td><td>{t.accountNumber ?? ""}</td><td>{t.createdBy}</td></tr>
                  ))}</tbody>
                </table>
              )}
              {canBuy && <PurchaseForm member={member} onDone={(m) => {
                setMember(m);
                setNotice(`${m.customerNumber} now holds ${formatAmount(m.holding)} in shares.`);
              }} />}
              {canAskTransfer && <TransferForm member={member} onDone={(t) => {
                setNotice(`Transfer of ${formatAmount(t.amount)} from ${t.fromCustomerNumber} to ${t.toCustomerNumber} asked for; `
                  + "it needs approval.");
                setTick((n) => n + 1);
              }} />}
            </>
          )}
        </Section>
      )}
      {ready && seesTransfers && <TransfersSection key={tick} canDecide={hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR")} />}
      {ready && seesRegister && <RegisterSection />}
    </>
  );
}

// ---------------------------------------------------------------------------------- dividends

function ShareSettingsForm({ settings, onSaved }: { settings: Settings | null; onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const [par, setPar] = useState(settings?.parValue ?? "100.00");
  const [minimum, setMinimum] = useState(settings?.minimumHolding ?? "0.00");
  const [capital, setCapital] = useState(settings?.shareCapitalAccount ?? "");
  const [source, setSource] = useState(settings?.dividendSourceAccount ?? "");
  const [payable, setPayable] = useState(settings?.dividendsPayableAccount ?? "");
  const [tax, setTax] = useState(settings?.dividendTaxAccount ?? "");
  const [rate, setRate] = useState(settings?.dividendTaxRate ?? "5.00");
  const issued = settings?.sharesIssued ?? false;
  async function save(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.put<Settings>(`${BASE}/settings`, {
      parValue: par.trim(), minimumHolding: minimum.trim(), shareCapitalAccount: capital.trim(), dividendSourceAccount: source.trim(),
      dividendsPayableAccount: payable.trim(), dividendTaxAccount: tax.trim(), dividendTaxRate: rate.trim(),
    }))) onSaved();
  }
  return (
    <Section title="Share settings">
      <form className="form" onSubmit={(e) => void save(e)}>
        <ProblemBox problem={action.error} />
        <label>Value of one share<input value={par} required inputMode="decimal" disabled={issued} onChange={(e) => setPar(e.target.value)} /></label>
        <label>Minimum holding<input value={minimum} required inputMode="decimal" onChange={(e) => setMinimum(e.target.value)} /></label>
        <label>Share capital account (equity)<input value={capital} required disabled={issued} onChange={(e) => setCapital(e.target.value)} /></label>
        <label>Dividends paid from (equity)<input value={source} required onChange={(e) => setSource(e.target.value)} /></label>
        <label>Dividends payable (liability)<input value={payable} required onChange={(e) => setPayable(e.target.value)} /></label>
        <label>Withholding tax payable (liability)<input value={tax} required onChange={(e) => setTax(e.target.value)} /></label>
        <label>Withholding tax rate (%)<input value={rate} required inputMode="decimal" onChange={(e) => setRate(e.target.value)} /></label>
        <div className="actions"><button type="submit" disabled={action.busy}>Save share settings</button></div>
        {issued && <p className="muted small">Shares have been issued, so the share value and the share capital account are fixed.</p>}
        {settings?.updatedBy && <p className="muted small">Last changed by {settings.updatedBy} {when(settings.updatedAt)}</p>}
        <p className="muted small">The withholding tax rate needs finance sign-off against current KRA rules.</p>
      </form>
    </Section>
  );
}

function DividendDecision({ run, onDone }: { run: Run; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (session && run.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    if (await action.run(() => api.post<Run>(`${BASE}/dividends/${run.runId}/${kind}`, { reason: note.trim() || undefined }))) onDone();
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for dividend run ${run.financialYear}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve and pay</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

/** Year-end dividends on share capital, asked by finance and approved by a second person (ADR-0040). */
export function DividendsPage() {
  const api = useApi();
  const { session } = useAuth();
  const isFinance = hasAnyRole(session, "FINANCE");
  const settings = useLoad<Settings>(`${BASE}/settings`);
  const runs = useLoad<Run[]>(`${BASE}/dividends`);
  const previewAction = useAction();
  const request = useAction();
  const download = useAction();
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const [recordDate, setRecordDate] = useState("");
  const [rate, setRate] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [key, setKey] = useState(newIdempotencyKey);
  const [notice, setNotice] = useState<string | null>(null);

  async function calculate(e: FormEvent) {
    e.preventDefault();
    setNotice(null);
    const p = await previewAction.run(() => api.post<Preview>(`${BASE}/dividends/preview`, {
      financialYear: Number(year), recordDate, ratePct: rate.trim(),
    }));
    setPreview(p ?? null);
  }

  async function requestRun() {
    if (!preview) return;
    const r = await request.run(() => api.post<Run>(`${BASE}/dividends`, {
      idempotencyKey: key, financialYear: preview.financialYear, recordDate: preview.recordDate, ratePct: preview.ratePct,
    }));
    if (r) {
      setNotice(`Dividend for ${r.financialYear} requested: ${formatAmount(r.netTotal)} net to ${r.members} members. `
        + "Another finance user or a supervisor must approve it.");
      setKey(newIdempotencyKey());
      setPreview(null);
      runs.reload();
    }
  }

  async function downloadLines(run: Run) {
    const lines = await download.run(() => api.get<Line[]>(`${BASE}/dividends/${run.runId}/lines`));
    if (lines) downloadCsv(`dividend-${run.financialYear}.csv`, [
      ["Member", "Name", "Shares held", "Gross", "Tax", "Net", "Paid to"],
      ...lines.map((l) => [l.customerNumber, l.name, l.sharesAmount, l.gross, l.tax, l.net, l.paidTo]),
    ]);
  }

  return (
    <>
      <h1>Dividends on share capital</h1>
      <p className="muted">Each member's dividend is the rate on the shares they held at the end of the record date, less withholding
        tax. It is paid into their oldest active savings account, or held as dividends payable if they have none.</p>
      {notice && <p className="ok" role="status">{notice}</p>}
      {settings.loading && !settings.data && !settings.error ? <Loading /> : (
        <>
          {!settings.data && <p className="warning">Share capital is not set up yet.</p>}
          {settings.data && isFinance && (
            <Section title="Calculate a dividend">
              <form className="form" onSubmit={(e) => void calculate(e)}>
                <label>Financial year<input type="number" min={2000} max={2100} value={year} required onChange={(e) => setYear(e.target.value)} /></label>
                <label>Record date (a closed business day)<input type="date" value={recordDate} required
                  onChange={(e) => setRecordDate(e.target.value)} /></label>
                <label>Dividend rate (%)<input value={rate} required inputMode="decimal" onChange={(e) => setRate(e.target.value)} /></label>
                <div className="actions"><button type="submit" disabled={previewAction.busy}>Calculate</button></div>
              </form>
              <ProblemBox problem={previewAction.error ?? request.error} />
              {preview && (
                <>
                  <dl className="facts">
                    <div><dt>Members</dt><dd>{preview.members}</dd></div>
                    <div><dt>Shares held</dt><dd>{formatAmount(preview.sharesTotal)}</dd></div>
                    <div><dt>Gross at {preview.ratePct}%</dt><dd>{formatAmount(preview.grossTotal)}</dd></div>
                    <div><dt>Withholding tax at {preview.taxRate}%</dt><dd>{formatAmount(preview.taxTotal)}</dd></div>
                    <div><dt>Net</dt><dd>{formatAmount(preview.netTotal)}</dd></div>
                    <div><dt>Into savings</dt><dd>{formatAmount(preview.toSavingsTotal)}</dd></div>
                    <div><dt>Held as payable</dt><dd>{formatAmount(preview.toPayableTotal)}</dd></div>
                  </dl>
                  <button type="button" disabled={request.busy || preview.members === 0} onClick={() => void requestRun()}>
                    Request dividend for {preview.financialYear}</button>
                </>
              )}
            </Section>
          )}
          <Section title="Dividend runs">
            <ProblemBox problem={runs.error ?? download.error} />
            {runs.loading && !runs.data ? <Loading /> : !runs.data?.length ? <Empty>No dividend runs yet.</Empty> : (
              <table>
                <thead><tr><th>Year</th><th>Record date</th><th>Rate</th><th>Status</th><th className="num">Gross</th>
                  <th className="num">Tax</th><th className="num">Net</th><th>Requested</th><th>Decided</th><th /></tr></thead>
                <tbody>{runs.data.map((r) => (
                  <tr key={r.runId}>
                    <td>{r.financialYear}</td><td>{r.recordDate}</td><td>{r.ratePct}%</td><td><Status value={r.status} /></td>
                    <td className="num">{formatAmount(r.grossTotal)}</td><td className="num">{formatAmount(r.taxTotal)}</td>
                    <td className="num">{formatAmount(r.netTotal)}<div className="muted small">{r.members} members</div></td>
                    <td>{r.requestedBy}<div className="muted small">{when(r.requestedAt)}</div></td>
                    <td>{r.decidedBy}{r.decisionReason && <div className="muted small">{r.decisionReason}</div>}</td>
                    <td>
                      {r.status === "REQUESTED" && <DividendDecision run={r} onDone={() => runs.reload()} />}
                      <button type="button" className="secondary" onClick={() => void downloadLines(r)}>Download members</button>
                    </td>
                  </tr>
                ))}</tbody>
              </table>
            )}
          </Section>
          {isFinance && <ShareSettingsForm key={settings.data?.updatedAt ?? "new"} settings={settings.data}
            onSaved={() => settings.reload()} />}
        </>
      )}
    </>
  );
}
