import { useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useApi } from "../api/ApiContext";
import type { Problem, Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { downloadCsv } from "../csv";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Account = Schemas["StatementAccount"];
type Upload = Schemas["StatementUploadView"];
type Detail = Schemas["StatementDetail"];
type LineView = Schemas["StatementLineView"];
type Rematch = Schemas["StatementRematch"];

const BASE = "/api/v1/payments/mpesa/statements";

/** Plain-language text for the reconciliation rules the database enforces (ADR-0038). */
const RULES: Record<string, string> = {
  RECON_DAY_OPEN: "This date is still the business date. Sign off after end of day has closed it.",
  RECON_EXCEPTIONS_OPEN: "Resolve every exception before signing off.",
  RECON_SAME_PERSON: "You resolved lines on this statement, so someone else must sign it off.",
  RECON_STALE: "The ledger changed while you were reviewing. Reload the statement and sign off again.",
  RECON_NOTE_REQUIRED: "There is a balance difference or ledger entries not on the statement. Add a note explaining them.",
  STATEMENT_LINK_INVALID: "The selected lines do not add up to what that entry moved on the clearing account, "
    + "or the entry belongs to an M-Pesa payment.",
  STATEMENT_INVALID: "The statement date is after the business date, or the short code is not set up.",
};

function explained(p: Problem | null): Problem | null {
  return p && p.code && RULES[p.code] ? { ...p, detail: RULES[p.code] } : p;
}

const EXCEPTION_TEXT: Record<string, string> = {
  NOT_IN_LEDGER: "Not on the ledger",
  AMOUNT_MISMATCH: "Amount differs from the ledger",
  DISBURSEMENT_ATTENTION: "Disbursement needs attention",
  ALREADY_MATCHED: "Already matched on another statement",
};

function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(new Error("The file could not be read"));
    reader.readAsText(file);
  });
}

function yesterday(): string {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function UploadSection({ onUploaded }: { onUploaded: (d: Detail) => void }) {
  const api = useApi();
  const accounts = useLoad<Account[]>(`${BASE}/accounts`);
  const action = useAction();
  const [account, setAccount] = useState("");
  const [date, setDate] = useState(yesterday);
  const [file, setFile] = useState<File | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    const [accountKind, shortCode] = account.split(":");
    const d = await action.run(async () => api.post<Detail>(BASE, {
      accountKind, shortCode, statementDate: date, fileName: file.name, csv: await readFile(file),
    }));
    if (d) onUploaded(d);
  }

  return (
    <Section title="Upload a statement">
      <p className="muted small">Export one day's statement from the M-Pesa business portal as CSV, for one paybill or B2C short code.</p>
      <ProblemBox problem={explained(accounts.error ?? action.error)} />
      <form className="form" onSubmit={(e) => void submit(e)}>
        <label>M-Pesa account
          <select value={account} required onChange={(e) => setAccount(e.target.value)}>
            <option value="">Choose the paybill or B2C account</option>
            {(accounts.data ?? []).map((a) => (
              <option key={`${a.accountKind}:${a.shortCode}`} value={`${a.accountKind}:${a.shortCode}`}>
                {a.accountKind === "PAYBILL" ? "Paybill" : "B2C"} {a.shortCode} (clearing {a.clearingAccount})</option>
            ))}
          </select></label>
        <label>Statement date<input type="date" value={date} required onChange={(e) => setDate(e.target.value)} /></label>
        <label>Statement file (CSV)
          <input type="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <div className="actions"><button type="submit" disabled={action.busy || !file || !account}>Upload and match</button></div>
      </form>
    </Section>
  );
}

function CheckFacts({ detail }: { detail: Detail }) {
  const u = detail.upload!;
  const c = detail.check;
  return (
    <>
      <dl className="facts">
        <div><dt>Opening balance</dt><dd>{formatAmount(u.openingBalance ?? "0")}</dd></div>
        <div><dt>Paid in</dt><dd>{formatAmount(u.totalPaidIn ?? "0")}</dd></div>
        <div><dt>Withdrawn</dt><dd>{formatAmount(u.totalWithdrawn ?? "0")}</dd></div>
        <div><dt>Closing balance (statement)</dt><dd>{formatAmount(u.closingBalance ?? "0")}</dd></div>
        {c && <div><dt>Clearing account {u.clearingAccount} (ledger)</dt><dd>{formatAmount(c.ledgerBalance ?? "0")}</dd></div>}
        {c && <div><dt>Difference</dt><dd className={c.difference === "0.00" ? "" : "warning"}>{formatAmount(c.difference ?? "0")}</dd></div>}
      </dl>
      {!u.balanceChainOk && (
        <p className="warning">The running balances in the file do not follow line by line. The closing balance was taken from the
          latest line; check the export is complete.</p>
      )}
      <p className="muted small">
        {u.matched} matched · {u.exceptions} open exceptions · {u.resolved} resolved · {u.ignored} not completed (ignored)
        {c && !c.live && " · figures as signed off"}
      </p>
    </>
  );
}

function LedgerOnly({ detail }: { detail: Detail }) {
  const c = detail.check;
  if (!c) return null;
  return (
    <Section title={`On the ledger, not on this statement (${c.ledgerOnlyCount ?? 0}, net ${formatAmount(c.ledgerOnlyAmount ?? "0")})`}>
      {!c.ledgerOnly?.length ? <Empty>Every clearing-account entry on this date is accounted for.</Empty> : (
        <table>
          <thead><tr><th>Business date</th><th>Source</th><th>Reference</th><th className="num">Amount</th><th /></tr></thead>
          <tbody>{c.ledgerOnly.map((i) => (
            <tr key={i.entryId}><td>{i.businessDate}</td><td>{i.source}</td><td>{i.reference}</td>
              <td className="num">{formatAmount(i.amount ?? "0")}</td>
              <td><Link to={`/ledger/entries/${i.entryId}`}>Entry</Link></td></tr>
          ))}</tbody>
        </table>
      )}
      <p className="muted small">Receipts recorded today often appear on the next day's statement. Anything else needs a note at sign-off.</p>
    </Section>
  );
}

function Exceptions({ detail, canResolve, onChange }: { detail: Detail; canResolve: boolean; onChange: (d: Detail) => void }) {
  const api = useApi();
  const id = detail.upload!.uploadId!;
  const lines = useLoad<LineView[]>(`${BASE}/${id}/lines?status=EXCEPTION`);
  const action = useAction();
  const [selected, setSelected] = useState<number[]>([]);
  const [note, setNote] = useState("");
  const [entry, setEntry] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const { reload } = lines;
  useEffect(() => { reload(); setSelected([]); }, [detail, reload]);

  function toggle(n: number) {
    setSelected(selected.includes(n) ? selected.filter((x) => x !== n) : [...selected, n]);
  }

  async function resolve(kind: "explain" | "link") {
    const body = kind === "link" ? { lineNos: selected, journalEntryId: entry.trim(), note: note.trim() }
      : { lineNos: selected, note: note.trim() };
    const d = await action.run(() => api.post<Detail>(`${BASE}/${id}/${kind}`, body));
    if (d) {
      setNotice(`${selected.length} line(s) resolved.`);
      setNote("");
      setEntry("");
      onChange(d);
    }
  }

  async function rematch() {
    const r = await action.run(() => api.post<Rematch>(`${BASE}/${id}/rematch`, {}));
    if (r) {
      setNotice(`Matching run again: ${r.matched} matched in total, ${r.stillOpen} still open.`);
      onChange(await api.get<Detail>(`${BASE}/${id}`));
    }
  }

  const total = (lines.data ?? []).filter((l) => selected.includes(l.lineNo ?? 0))
    .reduce((s, l) => s + Number(l.paidIn ?? 0) - Number(l.withdrawn ?? 0), 0);

  return (
    <Section title="Exceptions" actions={canResolve
      ? <button type="button" className="secondary" disabled={action.busy} onClick={() => void rematch()}>Match again</button> : undefined}>
      <ProblemBox problem={explained(lines.error ?? action.error)} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {lines.loading && !lines.data ? <Loading /> : !lines.data?.length ? <Empty>No open exceptions.</Empty> : (
        <>
          <table>
            <thead><tr>{canResolve && <th />}<th>Line</th><th>Receipt</th><th>Time</th><th>Details</th><th>Type</th>
              <th className="num">Paid in</th><th className="num">Withdrawn</th><th>Why</th></tr></thead>
            <tbody>{lines.data.map((l) => (
              <tr key={l.lineNo}>
                {canResolve && <td><input type="checkbox" aria-label={`Select line ${l.lineNo}`} checked={selected.includes(l.lineNo ?? 0)}
                  onChange={() => toggle(l.lineNo ?? 0)} /></td>}
                <td>{l.lineNo}</td><td>{l.receiptNo}</td><td>{when(l.completedAt)}</td><td>{l.details}</td>
                <td>{l.category}</td><td className="num">{formatAmount(l.paidIn ?? "0")}</td>
                <td className="num">{formatAmount(l.withdrawn ?? "0")}</td>
                <td>{EXCEPTION_TEXT[l.exceptionCode ?? ""] ?? l.exceptionCode}</td>
              </tr>
            ))}</tbody>
          </table>
          {canResolve && (
            <div className="form">
              <p className="muted small">{selected.length} selected, net {formatAmount(total.toFixed(2))}. Link them to the journal entry
                that booked them (for example the day's charges or the transfer to the bank), or explain them.</p>
              <label>Note (at least 10 characters)<input value={note} onChange={(e) => setNote(e.target.value)} /></label>
              <label>Journal entry id (to link)<input value={entry} onChange={(e) => setEntry(e.target.value)} /></label>
              <div className="actions">
                <button type="button" disabled={action.busy || !selected.length || note.trim().length < 10 || !entry.trim()}
                  onClick={() => void resolve("link")}>Link to entry</button>
                <button type="button" className="secondary" disabled={action.busy || !selected.length || note.trim().length < 10}
                  onClick={() => void resolve("explain")}>Explain</button>
              </div>
            </div>
          )}
        </>
      )}
    </Section>
  );
}

function SignOff({ detail, onChange }: { detail: Detail; onChange: (d: Detail) => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  const u = detail.upload!;
  if (session && u.uploadedBy === actorOf(session)) {
    return <p className="muted">You uploaded this statement, so another finance user or a supervisor signs it off.</p>;
  }
  async function sign() {
    const d = await action.run(() => api.post<Detail>(`${BASE}/${u.uploadId}/sign-off`, { note: note.trim() || undefined }));
    if (d) onChange(d);
  }
  return (
    <div className="form">
      <ProblemBox problem={explained(action.error)} />
      <label>Sign-off note (needed when there is a difference or ledger-only entries)
        <input value={note} onChange={(e) => setNote(e.target.value)} /></label>
      <div className="actions"><button type="button" disabled={action.busy || (u.exceptions ?? 0) > 0} onClick={() => void sign()}>
        Sign off {u.statementDate}</button></div>
    </div>
  );
}

function Withdraw({ detail, onChange }: { detail: Detail; onChange: (d: Detail) => void }) {
  const api = useApi();
  const action = useAction();
  const [reason, setReason] = useState("");
  async function withdraw() {
    const d = await action.run(() => api.post<Detail>(`${BASE}/${detail.upload!.uploadId}/withdraw`, { reason: reason.trim() }));
    if (d) onChange(d);
  }
  return (
    <div className="form">
      <ProblemBox problem={action.error} />
      <label>Reason to withdraw this upload<input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="actions"><button type="button" className="secondary" disabled={action.busy || reason.trim().length < 10}
        onClick={() => void withdraw()}>Withdraw upload</button></div>
    </div>
  );
}

function StatementView({ id, onChange }: { id: string; onChange: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const loaded = useLoad<Detail>(`${BASE}/${id}`);
  const download = useAction();
  const [detail, setDetail] = useState<Detail | null>(null);
  useEffect(() => { if (loaded.data) setDetail(loaded.data); }, [loaded.data]);
  if (loaded.error) return <ProblemBox problem={loaded.error} />;
  if (!detail?.upload) return <Loading />;
  const u = detail.upload;
  const finance = hasAnyRole(session, "FINANCE");
  const open = u.status === "OPEN";
  function changed(d: Detail) {
    setDetail(d);
    onChange();
  }
  async function downloadLines() {
    const lines = await download.run(() => api.get<LineView[]>(`${BASE}/${id}/lines?limit=5000`));
    if (lines) downloadCsv(`mpesa-${u.shortCode}-${u.statementDate}.csv`, [
      ["Line", "Receipt", "Completed", "Details", "Status", "Paid in", "Withdrawn", "Balance", "Type", "Result", "Exception",
        "Matched to", "Resolution", "Note", "Resolved by"],
      ...lines.map((l) => [l.lineNo, l.receiptNo, l.completedAt, l.details, l.transactionStatus, l.paidIn, l.withdrawn, l.balance,
        l.category, l.status, l.exceptionCode, l.matchedReference, l.resolution, l.resolutionNote, l.resolvedBy]),
    ]);
  }
  return (
    <>
      <Section title={`${u.accountKind === "PAYBILL" ? "Paybill" : "B2C"} ${u.shortCode} — ${u.statementDate}`}
        actions={<><Status value={u.status} />
          <button type="button" className="secondary" disabled={download.busy} onClick={() => void downloadLines()}>Download lines</button></>}>
        <ProblemBox problem={download.error} />
        <p className="muted small">{u.fileName}, uploaded by {u.uploadedBy} {when(u.uploadedAt)}
          {u.signedOffBy && <> · signed off by {u.signedOffBy} {when(u.signedOffAt)}</>}
          {u.withdrawnBy && <> · withdrawn by {u.withdrawnBy} {when(u.withdrawnAt)}</>}</p>
        {u.note && <p><strong>Note:</strong> {u.note}</p>}
        <CheckFacts detail={detail} />
        {open && <SignOff detail={detail} onChange={changed} />}
        {open && finance && <Withdraw detail={detail} onChange={changed} />}
      </Section>
      {u.status !== "WITHDRAWN" && <Exceptions detail={detail} canResolve={open && finance} onChange={changed} />}
      {u.status !== "WITHDRAWN" && <LedgerOnly detail={detail} />}
    </>
  );
}

export function MpesaStatementsPage() {
  const { session } = useAuth();
  const [params, setParams] = useSearchParams();
  const list = useLoad<Upload[]>(BASE);
  const id = params.get("id");
  return (
    <>
      <h1>M-Pesa statement reconciliation</h1>
      <p className="muted">Each day's M-Pesa statement is matched line by line to the payments and disbursements on the platform. What
        does not match is resolved, the clearing account is compared with the statement balance, and a second person signs the day off.</p>
      {hasAnyRole(session, "FINANCE") && <UploadSection onUploaded={(d) => { list.reload(); setParams({ id: d.upload!.uploadId! }); }} />}
      {id && <StatementView key={id} id={id} onChange={() => list.reload()} />}
      <Section title="Statements">
        <ProblemBox problem={list.error} />
        {list.loading && !list.data ? <Loading /> : !list.data?.length ? <Empty>No statements uploaded yet.</Empty> : (
          <table>
            <thead><tr><th>Date</th><th>Account</th><th>Status</th><th className="num">Matched</th><th className="num">Open</th>
              <th className="num">Closing</th><th>Signed off</th><th /></tr></thead>
            <tbody>{list.data.map((u) => (
              <tr key={u.uploadId}>
                <td>{u.statementDate}</td><td>{u.accountKind} {u.shortCode}</td><td><Status value={u.status} /></td>
                <td className="num">{u.matched}</td><td className="num">{u.exceptions}</td>
                <td className="num">{formatAmount(u.closingBalance ?? "0")}</td><td>{u.signedOffBy}</td>
                <td><button type="button" className="secondary" onClick={() => setParams({ id: u.uploadId! })}>Open</button></td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </Section>
    </>
  );
}
