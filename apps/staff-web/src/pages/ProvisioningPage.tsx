import { useEffect, useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { downloadCsv } from "../csv";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Settings = Schemas["EclSettingsView"];
type Rates = Schemas["EclRates"];
type RateRow = Schemas["EclRateRow"];
type Calculation = Schemas["EclCalculation"];
type Run = Schemas["EclRunView"];
type Line = Schemas["EclLine"];
type StageRow = Schemas["EclStageRow"];

const BASE = "/api/v1/provisioning";
const STAGE_TEXT = ["", "Stage 1 (performing)", "Stage 2 (credit risk up)", "Stage 3 (credit-impaired)"];

/** The last day of the month before today, as yyyy-mm-dd. */
function lastMonthEnd(): string {
  const d = new Date();
  const end = new Date(Date.UTC(d.getFullYear(), d.getMonth(), 0));
  return end.toISOString().slice(0, 10);
}

function StageTable({ stages, caption }: { stages: StageRow[]; caption: string }) {
  return (
    <table>
      <caption>{caption}</caption>
      <thead><tr><th>Stage</th><th className="num">Loans</th><th className="num">Exposure (principal)</th>
        <th className="num">Expected credit loss</th><th className="num">Coverage</th></tr></thead>
      <tbody>{stages.map((s) => (
        <tr key={s.stage}><td>{STAGE_TEXT[s.stage ?? 0]}</td><td className="num">{s.loans}</td>
          <td className="num">{formatAmount(s.exposure ?? "0")}</td><td className="num">{formatAmount(s.ecl ?? "0")}</td>
          <td className="num">{s.coveragePct}%</td></tr>
      ))}</tbody>
    </table>
  );
}

function SettingsSection() {
  const api = useApi();
  const settings = useLoad<Settings>(`${BASE}/settings`);
  const action = useAction();
  const [f, setF] = useState({ allowanceAccount: "", expenseAccount: "", stage2Dpd: 30, stage3Dpd: 90 });
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    const s = settings.data;
    if (s) setF({ allowanceAccount: s.allowanceAccount ?? "", expenseAccount: s.expenseAccount ?? "",
      stage2Dpd: s.stage2Dpd ?? 30, stage3Dpd: s.stage3Dpd ?? 90 });
  }, [settings.data]);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.put<Settings>(`${BASE}/settings`, f))) {
      setNotice("Provisioning settings saved.");
      settings.reload();
    }
  }

  return (
    <Section title="Accounts and stages" actions={<Status value={settings.data?.configured ? "SET" : "NOT_SET"} />}>
      <ProblemBox problem={settings.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <form className="form" onSubmit={(e) => void save(e)}>
        <label>Loss allowance account (an asset account of its own)
          <input value={f.allowanceAccount} required onChange={(e) => setF({ ...f, allowanceAccount: e.target.value })} /></label>
        <label>Impairment expense account
          <input value={f.expenseAccount} required onChange={(e) => setF({ ...f, expenseAccount: e.target.value })} /></label>
        <label>Stage 2 when days past due are above
          <input type="number" min={1} max={180} value={f.stage2Dpd} onChange={(e) => setF({ ...f, stage2Dpd: Number(e.target.value) })} /></label>
        <label>Stage 3 when days past due are above
          <input type="number" min={2} max={365} value={f.stage3Dpd} onChange={(e) => setF({ ...f, stage3Dpd: Number(e.target.value) })} /></label>
        <div className="actions"><button type="submit" disabled={action.busy}>Save settings</button></div>
      </form>
      {settings.data?.updatedBy && <p className="muted small">Last changed by {settings.data.updatedBy}, {when(settings.data.updatedAt)}</p>}
    </Section>
  );
}

function RatesSection() {
  const api = useApi();
  const rates = useLoad<Rates>(`${BASE}/rates`);
  const action = useAction();
  const [rows, setRows] = useState<RateRow[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => { if (rates.data?.rows) setRows(rates.data.rows); }, [rates.data]);

  function set(i: number, key: "stage1Pct" | "stage2Pct" | "stage3Pct", value: string) {
    setRows(rows.map((r, j) => (j === i ? { ...r, [key]: value } : r)));
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.put<Rates>(`${BASE}/rates`, { rows }))) {
      setNotice("Provision rates saved.");
      rates.reload();
    }
  }

  if (rates.loading && !rates.data) return <Loading />;
  return (
    <Section title="Provision rates (% of exposure)">
      <ProblemBox problem={rates.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <form onSubmit={(e) => void save(e)}>
        <table>
          <thead><tr><th>Product</th><th>Stage 1</th><th>Stage 2</th><th>Stage 3</th></tr></thead>
          <tbody>{rows.map((r, i) => (
            <tr key={r.productCode}>
              <td>{r.productCode === "*" ? "Default" : r.productCode}<div className="muted small">{r.productName}</div></td>
              {(["stage1Pct", "stage2Pct", "stage3Pct"] as const).map((k, s) => (
                <td key={k}><input aria-label={`${r.productCode === "*" ? "Default" : r.productCode} stage ${s + 1} rate`} inputMode="decimal"
                  value={r[k] ?? ""} onChange={(e) => set(i, k, e.target.value)} /></td>
              ))}
            </tr>
          ))}</tbody>
        </table>
        <p className="muted small">Leave a product's rate blank to use the default row. Rates need finance sign-off.</p>
        <div className="actions"><button type="submit" disabled={action.busy}>Save rates</button></div>
      </form>
    </Section>
  );
}

function RunDecision({ run, onDone }: { run: Run; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (session && run.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    if (await action.run(() => api.post<Run>(`${BASE}/runs/${run.runId}/${kind}`, { reason: note.trim() || undefined }))) onDone();
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for provision run ${run.asOf}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve and post</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

export function ProvisioningPage() {
  const api = useApi();
  const [asOf, setAsOf] = useState(lastMonthEnd);
  const [shown, setShown] = useState<string | null>(null);
  const calc = useLoad<Calculation>(shown ? `${BASE}/preview?asOf=${shown}` : null);
  const sasra = useLoad<Schemas["PrudentialReturn"]>(shown ? `/api/v1/regulatory/prudential?asOf=${shown}` : null);
  const runs = useLoad<Run[]>(`${BASE}/runs`);
  const request = useAction();
  const download = useAction();
  const [key, setKey] = useState(newIdempotencyKey);
  const [notice, setNotice] = useState<string | null>(null);
  const c = calc.data;

  async function requestRun() {
    const r = await request.run(() => api.post<Run>(`${BASE}/runs`, { idempotencyKey: key, asOf: shown }));
    if (r) {
      setNotice(`Provision run for ${r.asOf} requested: ${formatAmount(r.requiredTotal ?? "0")} required, change ${formatAmount(r.change ?? "0")}. `
        + "Another finance user must approve it.");
      setKey(newIdempotencyKey());
      runs.reload();
    }
  }

  async function downloadLines(run: Run) {
    const lines = await download.run(() => api.get<Line[]>(`${BASE}/runs/${run.runId}/lines`));
    if (lines) downloadCsv(`ecl-${run.asOf}.csv`, [
      ["Loan", "Customer", "Product", "Branch", "Days past due", "Stage", "Exposure", "Rate %", "ECL"],
      ...lines.map((l) => [l.loanNumber, l.customerNumber, l.productCode, l.branchCode, l.daysPastDue, l.stage, l.exposure, l.ratePct, l.ecl]),
    ]);
  }

  return (
    <>
      <h1>Loan loss provisioning (IFRS 9)</h1>
      <p className="muted">Simplified approach: each loan is staged by days past due at a closed month end and provided at its
        product's rate for that stage on its outstanding principal. Approval posts only the change in the allowance.</p>
      {notice && <p className="ok" role="status">{notice}</p>}
      <Section title="Calculate">
        <form className="form" onSubmit={(e) => { e.preventDefault(); setShown(asOf); setNotice(null); }}>
          <label>As at (a closed business day)<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
          <div className="actions"><button type="submit">Calculate</button></div>
        </form>
        <ProblemBox problem={calc.error ?? request.error} />
        {calc.loading && <Loading />}
        {c && (
          <>
            <StageTable stages={c.stages ?? []} caption={`Expected credit loss at ${c.asOf} (${c.loans} loans)`} />
            <dl className="facts">
              <div><dt>Required allowance</dt><dd>{formatAmount(c.requiredTotal ?? "0")}</dd></div>
              <div><dt>Allowance held now</dt><dd>{formatAmount(c.currentAllowance ?? "0")}</dd></div>
              <div><dt>Change to post</dt><dd>{formatAmount(c.change ?? "0")}</dd></div>
              {sasra.data?.classification?.requiredAllowance && (
                <div><dt>SASRA required allowance (comparison)</dt><dd>{formatAmount(sasra.data.classification.requiredAllowance)}</dd></div>
              )}
            </dl>
            {c.productsWithoutRates && c.productsWithoutRates.length > 0 && (
              <p className="warning">No rate for {c.productsWithoutRates.join(", ")}: set them (or default rates) before requesting a run.</p>
            )}
            {c.monthEnd
              ? <button type="button" disabled={request.busy || (c.productsWithoutRates?.length ?? 0) > 0} onClick={() => void requestRun()}>
                  Request provision run for {c.asOf}</button>
              : <p className="muted small">Provision runs are posted for month ends only; this is an estimate.</p>}
          </>
        )}
      </Section>
      <Section title="Provision runs">
        <ProblemBox problem={runs.error ?? download.error} />
        {runs.loading && !runs.data ? <Loading /> : !runs.data?.length ? <Empty>No provision runs yet.</Empty> : (
          <table>
            <thead><tr><th>Month end</th><th>Status</th><th className="num">Required</th><th className="num">Change</th>
              <th>Requested</th><th>Decided</th><th /></tr></thead>
            <tbody>{runs.data.map((r) => (
              <tr key={r.runId}>
                <td>{r.asOf}</td><td><Status value={r.status ?? ""} /></td>
                <td className="num">{formatAmount(r.requiredTotal ?? "0")}</td><td className="num">{formatAmount(r.change ?? "0")}</td>
                <td>{r.requestedBy}<div className="muted small">{when(r.requestedAt)}</div></td>
                <td>{r.decidedBy}{r.decisionReason && <div className="muted small">{r.decisionReason}</div>}</td>
                <td>
                  {r.status === "REQUESTED" && <RunDecision run={r} onDone={() => runs.reload()} />}
                  <button type="button" className="secondary" onClick={() => void downloadLines(r)}>Download loans</button>
                </td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </Section>
      <SettingsSection />
      <RatesSection />
    </>
  );
}
