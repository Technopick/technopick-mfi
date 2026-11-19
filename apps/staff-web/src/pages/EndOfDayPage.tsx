import { useState } from "react";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type EodStatus = Schemas["EndOfDayStatus"];
type EodSummary = Schemas["EndOfDaySummary"];
const EOD = "/api/v1/end-of-day";

/** Close the business day: shows what blocks it, runs it (OPERATIONS) and shows what it posted. */
export function EndOfDayPage() {
  const api = useApi();
  const { session } = useAuth();
  const status = useLoad<EodStatus>(EOD);
  const action = useAction();
  const [summary, setSummary] = useState<EodSummary | null>(null);
  const canRun = hasAnyRole(session, "OPERATIONS");
  const s = status.data;
  const tills = s?.openTills ?? [];
  const blocked = tills.length > 0 || s?.runStatus === "RUNNING";

  async function run() {
    const r = await action.run(() => api.post<EodSummary>(`${EOD}/run`));
    if (r) setSummary(r);
    status.reload();
  }

  return (
    <>
      <h1>End of day</h1>
      <ProblemBox problem={status.error ?? action.error} />
      {summary && (
        <p className="ok" role="status">
          Business day {summary.closedDate} closed; the business date is now {summary.newBusinessDate}.
          {" "}Loans: {summary.loansProcessed} processed, interest accrued {summary.loanInterestAccrued}, penalties{" "}
          {summary.penaltiesCharged}, {summary.loansInArrears} in arrears, {summary.loansClosed} closed (credit applied{" "}
          {summary.creditApplied}).
          {(summary.reconciliationDifferences ?? 0) > 0 && <> {summary.reconciliationDifferences} control account(s) did not reconcile.</>} Savings interest accrued:{" "}
          {summary.interestAccrued} on {summary.accountsAccrued} account(s).
          {(summary.accountsCredited ?? 0) > 0 && <> Interest credited: {summary.interestCredited} to {summary.accountsCredited} account(s), tax withheld {summary.taxWithheld}.</>}
        </p>
      )}
      {status.loading && !s ? <Loading /> : s && (
        <Section title={`Business date ${s.businessDate}`}>
          <dl className="facts">
            <dt>Last closed day</dt><dd>{s.lastClosedDate ?? "none yet"}</dd>
            <dt>This day's run</dt><dd>{s.runStatus ? <Status value={s.runStatus} /> : "not started"}{s.runStartedAt && <> - {when(s.runStartedAt)}</>}</dd>
            <dt>Loans</dt><dd>{s.activeLoans ?? 0} active: interest accrual, penalties and arrears</dd>
            <dt>Savings interest</dt><dd>{s.interestPostingDay ? "accrued, then credited to accounts (month end)" : "accrued"}</dd>
          </dl>
          {s.runStatus === "FAILED" && s.failureReason && <p className="problem">Last attempt failed: {s.failureReason}. Fix the cause and run again; finished steps are not repeated.</p>}
          <h3>Tills still open</h3>
          {!tills.length ? <Empty>All tills are closed and balanced.</Empty> : (
            <table>
              <thead><tr><th>Till</th><th>Teller</th><th>Status</th></tr></thead>
              <tbody>{tills.map((t) => <tr key={t.tillCode}><td>{t.tillCode}</td><td>{t.teller}</td><td><Status value={t.status} /></td></tr>)}</tbody>
            </table>
          )}
          {canRun && (
            <button type="button" disabled={action.busy || blocked} onClick={() => void run()}>
              {action.busy ? "Closing the day..." : `Close business day ${s.businessDate}`}
            </button>
          )}
        </Section>
      )}
    </>
  );
}
