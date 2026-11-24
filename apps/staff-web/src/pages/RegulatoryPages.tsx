import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { Schemas } from "../api/client";
import { downloadCsv, type Cell } from "../csv";
import { useApi } from "../api/ApiContext";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type Defined<T> = { [K in keyof T]-?: NonNullable<T[K]> };
/** The server always sends these parts; the generated types mark every field optional. */
type PrudentialReturn = Defined<Schemas["PrudentialReturn"]> & {
  classification: Defined<Schemas["RiskClassification"]>; capital: Defined<Schemas["CapitalAdequacy"]>; liquidity: Defined<Schemas["Liquidity"]>;
};
type ReturnLine = Schemas["ReturnLine"];
type RatioCheck = Schemas["RatioCheck"];
type ClassifiedLoan = Schemas["ClassifiedLoan"];
type SettingsView = Schemas["PrudentialSettingsView"];
type SettingsRequest = Schemas["PrudentialSettingsRequest"];
type Mapping = Schemas["RegulatoryMapping"];
const BASE = "/api/v1/regulatory";
const fmt = (v?: string | null) => (v == null ? "" : Number(v).toLocaleString("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
const CLASS_LABEL: Record<string, string> = { PERFORMING: "Performing", WATCH: "Watch", SUBSTANDARD: "Substandard", DOUBTFUL: "Doubtful", LOSS: "Loss" };

function Lines({ lines }: { lines?: ReturnLine[] }) {
  return (
    <table>
      <thead><tr><th>Line</th><th className="num">Amount</th><th>From accounts</th></tr></thead>
      <tbody>{(lines ?? []).map((l) => (
        <tr key={l.line}>
          <td>{l.label}</td>
          <td className="num">{fmt(l.amount)}</td>
          <td>{!l.accounts?.length ? <span className="muted">none mapped</span> : l.accounts.map((a) => (
            <div key={a.accountCode}><Link to={`/ledger/accounts/${encodeURIComponent(a.accountCode ?? "")}`}>{a.accountCode}</Link> {fmt(a.amount)}</div>
          ))}</td>
        </tr>
      ))}</tbody>
    </table>
  );
}

function Checks({ checks }: { checks?: RatioCheck[] }) {
  return (
    <table>
      <thead><tr><th>Requirement</th><th className="num">Figure</th><th className="num">Minimum</th><th>Result</th></tr></thead>
      <tbody>{(checks ?? []).map((c) => (
        <tr key={c.code}>
          <td>{c.label}{c.denominator != null && <div className="muted small">{fmt(c.numerator)} / {fmt(c.denominator)}</div>}</td>
          <td className="num">{c.valuePct != null ? `${c.valuePct}%` : c.denominator == null ? fmt(c.numerator) : "n/a"}</td>
          <td className="num">{c.denominator == null ? fmt(c.minimum) : `${c.minimum}%`}</td>
          <td><span className={`status ${c.met === true ? "status-completed" : c.met === false ? "status-failed" : ""}`}>
            {c.met === true ? "Met" : c.met === false ? "Not met" : "Nothing to measure"}</span></td>
        </tr>
      ))}</tbody>
    </table>
  );
}

function csvRows(r: PrudentialReturn): Cell[][] {
  const rows: Cell[][] = [["Return", "Item", "Account", "Amount", "Figure %", "Minimum", "Met"]];
  const lines = (ret: string, ls?: ReturnLine[]) => (ls ?? []).forEach((l) => {
    rows.push([ret, l.label, "", l.amount]);
    (l.accounts ?? []).forEach((a) => rows.push([ret, l.label, a.accountCode, a.amount]));
  });
  const checks = (ret: string, cs?: RatioCheck[]) => (cs ?? []).forEach((c) =>
    rows.push([ret, c.label, "", c.numerator, c.valuePct, c.minimum, c.met == null ? "" : c.met ? "yes" : "no"]));
  lines("Capital adequacy", r.capital.lines);
  rows.push(["Capital adequacy", "Core capital", "", r.capital.coreCapital], ["Capital adequacy", "Institutional capital", "", r.capital.institutionalCapital],
    ["Capital adequacy", "Total assets", "", r.capital.totalAssets]);
  checks("Capital adequacy", r.capital.checks);
  lines("Liquidity", r.liquidity.lines);
  checks("Liquidity", r.liquidity.checks);
  (r.classification.rows ?? []).forEach((c) => rows.push(["Risk classification", `${CLASS_LABEL[c.loanClass ?? ""] ?? c.loanClass} (${c.daysPastDue} days, ${c.loans} loans)`,
    "", c.outstandingPrincipal, c.allowancePct, c.requiredAllowance]));
  rows.push(["Risk classification", "Interest in suspense", "", r.classification.interestInSuspense],
    ["Risk classification", "Interest on non-performing loans still in income", "", r.classification.interestAccruedOnNonPerforming],
    ["Risk classification", "Required allowance for loan loss", "", r.classification.requiredAllowance],
    ["Risk classification", "Allowance held", "", r.classification.allowanceHeld.amount],
    ["Risk classification", "Shortfall", "", r.classification.allowanceShortfall],
    ["Risk classification", "Non-performing loans", "", r.classification.nonPerformingLoans, r.classification.nonPerformingRatioPct]);
  return rows;
}

/** SASRA prudential returns for a closed business date (ADR-0030). */
export function PrudentialPage() {
  const [params, setParams] = useSearchParams();
  const [asOf, setAsOf] = useState(params.get("asOf") ?? "");
  const [loanClass, setLoanClass] = useState<string | null>(null);
  const q = params.get("asOf") ? `?asOf=${params.get("asOf")}` : "";
  const ret = useLoad<PrudentialReturn>(`${BASE}/prudential${q}`);
  const r = ret.data;
  const loans = useLoad<ClassifiedLoan[]>(r && loanClass ? `${BASE}/prudential/loans?asOf=${r.asOf}&loanClass=${loanClass}` : null);
  const { session } = useAuth();

  return (
    <>
      <p><Link to="/ledger">General ledger</Link>{hasAnyRole(session, "FINANCE") && <> · <Link to="/regulatory/setup">Settings and account mapping</Link></>}</p>
      <h1>Prudential returns</h1>
      <form className="search" onSubmit={(e) => {
        e.preventDefault();
        setLoanClass(null);
        setParams(asOf ? new URLSearchParams({ asOf }) : new URLSearchParams());
      }}>
        <label>As at (a closed business day)<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
        <button type="submit">Show</button>
      </form>
      <ProblemBox problem={ret.error} />
      {ret.loading && !r ? <Loading /> : r && (
        <>
          <p role="status" className={r.periodClosed ? "ok" : "warning"}>
            As at {r.asOf}, in {r.currency}. {r.periodClosed ? "The period is closed: these figures are final." : "Provisional: the period is still open."}
            {r.settings.regulationDefaults && " Using the regulation defaults (no tenant settings saved)."}
          </p>
          {(r.notes ?? []).filter((n) => !n.includes("still open")).map((n) => <p key={n} className="warning">{n}</p>)}
          <p><button type="button" className="secondary" onClick={() => downloadCsv(`prudential-return-${r.asOf}.csv`, csvRows(r))}>Download CSV</button></p>

          <Section title="Capital adequacy (Regulation 9)">
            <Checks checks={r.capital.checks} />
            <p className="muted small">Core capital {fmt(r.capital.coreCapital)}; institutional capital {fmt(r.capital.institutionalCapital)};
              {" "}total assets {fmt(r.capital.totalAssets)}; total deposits {fmt(r.capital.totalDeposits)}.</p>
            <Lines lines={r.capital.lines} />
          </Section>

          <Section title="Liquidity (Regulation 13)">
            <Checks checks={r.liquidity.checks} />
            <Lines lines={r.liquidity.lines} />
          </Section>

          <Section title="Risk classification of loans and allowance for loan loss (Regulations 41, 42 and 44)">
            <table>
              <thead><tr><th>Class</th><th>Days past due</th><th className="num">Loans</th><th className="num">Outstanding principal</th>
                <th className="num">Allowance %</th><th className="num">Required allowance</th></tr></thead>
              <tbody>{(r.classification.rows ?? []).map((c) => (
                <tr key={c.loanClass}>
                  <td>{(c.loans ?? 0) > 0 ? <button type="button" className="link" onClick={() => setLoanClass(c.loanClass ?? null)}>{CLASS_LABEL[c.loanClass ?? ""]}</button>
                    : CLASS_LABEL[c.loanClass ?? ""]}</td>
                  <td>{c.daysPastDue}</td><td className="num">{c.loans}</td><td className="num">{fmt(c.outstandingPrincipal)}</td>
                  <td className="num">{c.allowancePct}</td><td className="num">{fmt(c.requiredAllowance)}</td>
                </tr>
              ))}</tbody>
              <tfoot><tr><th colSpan={2}>Total</th><th className="num">{r.classification.totalLoans}</th><th className="num">{fmt(r.classification.grossLoans)}</th>
                <th /><th className="num">{fmt(r.classification.requiredAllowance)}</th></tr></tfoot>
            </table>
            <dl className="facts">
              <dt>Allowance held</dt><dd>{fmt(r.classification.allowanceHeld.amount)}</dd>
              <dt>Shortfall</dt><dd className={Number(r.classification.allowanceShortfall) > 0 ? "problem" : ""}>{fmt(r.classification.allowanceShortfall)}</dd>
              <dt>Non-performing loans</dt><dd>{fmt(r.classification.nonPerformingLoans)} ({r.classification.nonPerformingRatioPct ?? "n/a"}% of gross loans)</dd>
              <dt>Interest in suspense</dt><dd>{fmt(r.classification.interestInSuspense)}</dd>
              <dt>Interest on non-performing loans still in income</dt><dd>{fmt(r.classification.interestAccruedOnNonPerforming)}</dd>
              <dt>Loan principal in the ledger</dt><dd>{fmt(r.classification.ledgerLoanPrincipal)}
                {Number(r.classification.unexplainedPrincipalDifference) !== 0 && <span className="problem"> (differs by {fmt(r.classification.unexplainedPrincipalDifference)})</span>}</dd>
            </dl>
            {loanClass && (
              <div>
                <h3>{CLASS_LABEL[loanClass]} loans</h3>
                {loans.loading && !loans.data ? <Loading /> : <ProblemBox problem={loans.error} />}
                {loans.data && (
                  <>
                    <p><button type="button" className="secondary" onClick={() => downloadCsv(`loans-${loanClass.toLowerCase()}-${r.asOf}.csv`, [
                      ["Loan", "Branch", "Days past due", "Class", "Outstanding principal", "Required allowance"],
                      ...loans.data!.map((l): Cell[] => [l.loanNumber, l.branchCode, l.daysPastDue, l.loanClass, l.outstandingPrincipal, l.requiredAllowance])])}>
                      Download these loans</button></p>
                    <table>
                      <thead><tr><th>Loan</th><th>Branch</th><th className="num">Days past due</th><th className="num">Outstanding principal</th><th className="num">Required allowance</th></tr></thead>
                      <tbody>{loans.data.map((l) => (
                        <tr key={l.loanNumber}><td><Link to={`/loans/${encodeURIComponent(l.loanNumber ?? "")}`}>{l.loanNumber}</Link></td><td>{l.branchCode}</td>
                          <td className="num">{l.daysPastDue}</td><td className="num">{fmt(l.outstandingPrincipal)}</td><td className="num">{fmt(l.requiredAllowance)}</td></tr>
                      ))}</tbody>
                    </table>
                  </>
                )}
              </div>
            )}
          </Section>

          {(r.unmappedBalanceSheetAccounts ?? []).length > 0 && (
            <Section title="Balance-sheet accounts not used in any line">
              <table><tbody>{r.unmappedBalanceSheetAccounts!.map((a) => (
                <tr key={a.accountCode}><td>{a.accountCode}</td><td>{a.name}</td><td className="num">{fmt(a.amount)}</td></tr>
              ))}</tbody></table>
            </Section>
          )}
          <p className="muted small">Prepared from the ledger and the end-of-day arrears of {r.asOf}. Check against SASRA&apos;s current forms before submitting.</p>
        </>
      )}
    </>
  );
}

const SETTING_FIELDS: [keyof SettingsRequest, string][] = [
  ["minCoreCapital", "Minimum core capital"], ["minCoreToAssetsPct", "Core capital to total assets, minimum %"],
  ["minInstitutionalToAssetsPct", "Institutional capital to total assets, minimum %"], ["minCoreToDepositsPct", "Core capital to deposits, minimum %"],
  ["minLiquidityPct", "Liquidity, minimum %"], ["watchMaxDpd", "Watch: up to days past due"], ["substandardMaxDpd", "Substandard: up to days past due"],
  ["doubtfulMaxDpd", "Doubtful: up to days past due"], ["provisionPerformingPct", "Allowance, performing %"], ["provisionWatchPct", "Allowance, watch %"],
  ["provisionSubstandardPct", "Allowance, substandard %"], ["provisionDoubtfulPct", "Allowance, doubtful %"], ["provisionLossPct", "Allowance, loss %"],
];
const INT_FIELDS = new Set<keyof SettingsRequest>(["watchMaxDpd", "substandardMaxDpd", "doubtfulMaxDpd"]);

/** Finance sets the tenant's prudential settings and maps ledger accounts to return lines. */
export function RegulatorySetupPage() {
  const api = useApi();
  const settings = useLoad<SettingsView>(`${BASE}/settings`);
  const mapping = useLoad<Mapping>(`${BASE}/mapping`);
  const saveSettings = useAction();
  const saveMapping = useAction();
  const [form, setForm] = useState<SettingsRequest | null>(null);
  const [changes, setChanges] = useState<Record<string, string | null>>({});
  const [saved, setSaved] = useState<string | null>(null);
  const s = settings.data;

  function edit() {
    if (!s) return;
    setSaved(null);
    setForm({ minCoreCapital: s.minCoreCapital, minCoreToAssetsPct: s.minCoreToAssetsPct, minInstitutionalToAssetsPct: s.minInstitutionalToAssetsPct,
      minCoreToDepositsPct: s.minCoreToDepositsPct, minLiquidityPct: s.minLiquidityPct, watchMaxDpd: s.watchMaxDpd, substandardMaxDpd: s.substandardMaxDpd,
      doubtfulMaxDpd: s.doubtfulMaxDpd, provisionPerformingPct: s.provisionPerformingPct, provisionWatchPct: s.provisionWatchPct,
      provisionSubstandardPct: s.provisionSubstandardPct, provisionDoubtfulPct: s.provisionDoubtfulPct, provisionLossPct: s.provisionLossPct,
      includeCurrentYearSurplus: s.includeCurrentYearSurplus, interestSuspensionEnabled: s.interestSuspensionEnabled,
      interestSuspenseAccount: s.interestSuspenseAccount });
  }

  async function submitSettings() {
    if (!form) return;
    if (await saveSettings.run(() => api.put<SettingsView>(`${BASE}/settings`, form))) {
      setForm(null);
      setSaved("Settings saved.");
      settings.reload();
    }
  }

  async function submitMapping() {
    const list = Object.entries(changes).map(([accountCode, line]) => ({ accountCode, line }));
    if (!list.length) return;
    if (await saveMapping.run(() => api.put<Mapping>(`${BASE}/mapping`, { changes: list }))) {
      setChanges({});
      setSaved("Account mapping saved.");
      mapping.reload();
    }
  }

  const lines = mapping.data?.lines ?? [];
  return (
    <>
      <p><Link to="/regulatory">Prudential returns</Link></p>
      <h1>Prudential settings and account mapping</h1>
      {saved && <p className="ok" role="status">{saved}</p>}
      <Section title="Settings" actions={s && !form ? <button type="button" onClick={edit}>Change settings</button> : undefined}>
        {settings.loading && !s ? <Loading /> : <ProblemBox problem={settings.error} />}
        {s && !form && (
          <>
            <p className={s.regulationDefaults ? "warning" : "muted small"}>{s.regulationDefaults
              ? "These are the defaults from the Deposit-taking Sacco Business Regulations, 2010. Compliance should confirm them for this Sacco."
              : `Last changed by ${s.updatedBy}.`}</p>
            <dl className="facts">
              {SETTING_FIELDS.map(([k, label]) => <span key={k}><dt>{label}</dt><dd>{String(s[k as keyof SettingsView] ?? "")}</dd></span>)}
              <dt>Count earnings not yet closed to equity as capital</dt><dd>{s.includeCurrentYearSurplus ? "yes" : "no"}</dd>
              <dt>Suspend interest on non-performing loans (Reg 42)</dt>
              <dd>{s.interestSuspensionEnabled ? `yes, into ${s.interestSuspenseAccount}` : "no"}</dd>
            </dl>
          </>
        )}
        {form && (
          <div className="form">
            {SETTING_FIELDS.map(([k, label]) => (
              <label key={k}>{label}<input inputMode="decimal" value={String(form[k] ?? "")} onChange={(e) =>
                setForm({ ...form, [k]: INT_FIELDS.has(k) ? Number(e.target.value) : e.target.value })} /></label>
            ))}
            <label><input type="checkbox" checked={form.includeCurrentYearSurplus ?? false}
              onChange={(e) => setForm({ ...form, includeCurrentYearSurplus: e.target.checked })} /> Count earnings not yet closed to equity as capital</label>
            <label><input type="checkbox" checked={form.interestSuspensionEnabled ?? false}
              onChange={(e) => setForm({ ...form, interestSuspensionEnabled: e.target.checked })} /> Suspend interest on non-performing loans (Reg 42)</label>
            <label>Interest in suspense account (an asset account used for nothing else)
              <select value={form.interestSuspenseAccount ?? ""} onChange={(e) => setForm({ ...form, interestSuspenseAccount: e.target.value || undefined })}>
                <option value="">(none)</option>
                {(mapping.data?.accounts ?? []).filter((a) => a.accountClass === "ASSET" && (!a.line || a.accountCode === form.interestSuspenseAccount))
                  .map((a) => <option key={a.accountCode} value={a.accountCode}>{a.accountCode} {a.name}</option>)}
              </select></label>
            <p className="wide muted small">Takes effect at the next end of day: unpaid interest on loans past the watch band moves out of income into
              suspense, and comes back to income when it is paid. Switching off releases it all to income.</p>
            <ProblemBox problem={saveSettings.error} />
            <div className="actions">
              <button type="button" disabled={saveSettings.busy} onClick={() => void submitSettings()}>Save settings</button>
              <button type="button" onClick={() => setForm(null)}>Cancel</button>
            </div>
          </div>
        )}
      </Section>
      <Section title="Which ledger accounts feed which line">
        {mapping.loading && !mapping.data ? <Loading /> : <ProblemBox problem={mapping.error} />}
        {mapping.data && (!mapping.data.accounts?.length ? <Empty>No balance-sheet accounts.</Empty> : (
          <>
            <table>
              <thead><tr><th>Account</th><th>Name</th><th>Class</th><th>Return line</th></tr></thead>
              <tbody>{mapping.data.accounts.map((a) => {
                const code = a.accountCode ?? "";
                const current = code in changes ? changes[code] : a.line ?? null;
                return (
                  <tr key={a.accountCode}>
                    <td>{a.accountCode}</td><td>{a.name}</td><td>{a.accountClass}</td>
                    <td><select aria-label={`Return line for ${a.accountCode}`} value={current ?? ""}
                      onChange={(e) => setChanges({ ...changes, [code]: e.target.value || null })}>
                      <option value="">(not used)</option>
                      {lines.filter((l) => l.accountClass === a.accountClass).map((l) => <option key={l.line} value={l.line}>{l.description}</option>)}
                    </select></td>
                  </tr>
                );
              })}</tbody>
            </table>
            <ProblemBox problem={saveMapping.error} />
            <p><button type="button" disabled={saveMapping.busy || !Object.keys(changes).length} onClick={() => void submitMapping()}>
              Save mapping{Object.keys(changes).length ? ` (${Object.keys(changes).length} change(s))` : ""}</button></p>
          </>
        ))}
      </Section>
    </>
  );
}
