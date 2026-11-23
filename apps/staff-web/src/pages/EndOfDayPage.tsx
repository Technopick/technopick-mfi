import { type ChangeEvent, useState } from "react";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type EodStatus = Schemas["EndOfDayStatus"];
type EodSummary = Schemas["EndOfDaySummary"];
type EodSchedule = Schemas["EndOfDaySchedule"];
type EodScheduleRequest = Schemas["EndOfDayScheduleRequest"];
type EodAttempt = Schemas["EndOfDayAttempt"];
type EodAlert = Schemas["EndOfDayAlert"];
const EOD = "/api/v1/end-of-day";

/** Close the business day: shows what blocks it, runs it (OPERATIONS) and shows what it posted. */
const secs = (ms?: number) => ((ms ?? 0) / 1000).toFixed(1);

export function EndOfDayPage() {
  const api = useApi();
  const { session } = useAuth();
  const status = useLoad<EodStatus>(EOD);
  const alerts = useLoad<EodAlert[]>(`${EOD}/alerts`);
  const attempts = useLoad<EodAttempt[]>(`${EOD}/attempts?limit=10`);
  const action = useAction();
  const [summary, setSummary] = useState<EodSummary | null>(null);
  const canRun = hasAnyRole(session, "OPERATIONS");
  const s = status.data;
  const tills = s?.openTills ?? [];
  const runningNow = s?.runStatus === "RUNNING" && s.runInProgress === true;
  const interrupted = s?.runStatus === "RUNNING" && s.runInProgress !== true;
  const blocked = tills.length > 0 || runningNow;

  async function run() {
    const r = await action.run(() => api.post<EodSummary>(`${EOD}/run`));
    if (r) setSummary(r);
    status.reload();
    alerts.reload();
    attempts.reload();
  }

  return (
    <>
      <h1>End of day</h1>
      {(alerts.data ?? []).map((a) => (
        <p key={a.code} className={a.severity === "CRITICAL" ? "problem" : "warning"} role="alert">
          <strong>{a.severity === "CRITICAL" ? "Needs action now" : "Attention"}:</strong> {a.message}
        </p>
      ))}
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
          {summary.timings && <> The run took {secs(summary.timings.totalMs)} s (loans {secs(summary.timings.loansMs)} s,
            savings {secs((summary.timings.savingsAccrualMs ?? 0) + (summary.timings.interestCreditMs ?? 0))} s).</>}
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
          {runningNow && <p className="warning">End of day is running now. It is never started a second time beside itself.</p>}
          {interrupted && <p className="problem">The run for this day stopped part-way because the process running it ended. Close the day again to resume it; finished steps are not repeated.</p>}
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
              {action.busy ? "Closing the day..." : `${interrupted ? "Resume closing" : "Close"} business day ${s.businessDate}`}
            </button>
          )}
        </Section>
      )}
      <SchedulePanel canEdit={hasAnyRole(session, "SUPERVISOR")} />
      <Section title="Recent attempts">
        {attempts.loading && !attempts.data ? <Loading /> : !attempts.data?.length ? <Empty>No end-of-day attempts yet.</Empty> : (
          <table>
            <thead><tr><th>Business date</th><th>Started</th><th>By</th><th>Outcome</th><th>Reason</th></tr></thead>
            <tbody>{attempts.data.map((a) => (
              <tr key={a.attemptId}>
                <td>{a.businessDate}</td><td>{when(a.startedAt)}</td>
                <td>{a.trigger === "SCHEDULED" ? "schedule" : a.actor}</td>
                <td><Status value={a.outcome} /></td><td>{a.reason ?? ""}</td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </Section>
    </>
  );
}

/** The automatic end-of-day schedule; supervisors change it. */
function SchedulePanel({ canEdit }: { canEdit: boolean }) {
  const api = useApi();
  const schedule = useLoad<EodSchedule>(`${EOD}/schedule`);
  const action = useAction();
  const [form, setForm] = useState<EodScheduleRequest | null>(null);
  const [saved, setSaved] = useState(false);
  const sc = schedule.data;

  function edit() {
    if (!sc) return;
    setSaved(false);
    setForm({ enabled: sc.enabled, runAt: sc.runAt, overrunMinutes: sc.overrunMinutes, maxAttempts: sc.maxAttempts,
      retryMinutes: sc.retryMinutes, maxCatchUpDays: sc.maxCatchUpDays });
  }

  async function save() {
    if (!form) return;
    const r = await action.run(() => api.put<EodSchedule>(`${EOD}/schedule`, form));
    if (r) {
      setForm(null);
      setSaved(true);
      schedule.reload();
    }
  }

  const num = (k: "overrunMinutes" | "maxAttempts" | "retryMinutes" | "maxCatchUpDays") => (e: ChangeEvent<HTMLInputElement>) =>
    form && setForm({ ...form, [k]: Number(e.target.value) });

  return (
    <Section title="Automatic schedule" actions={canEdit && sc && !form ? <button type="button" onClick={edit}>Change schedule</button> : undefined}>
      {schedule.loading && !sc ? <Loading /> : <ProblemBox problem={schedule.error} />}
      {saved && <p className="ok" role="status">Schedule saved.</p>}
      {sc && !form && (
        <dl className="facts">
          <dt>Runs automatically</dt><dd>{sc.enabled ? `yes, daily at ${sc.runAt} (${sc.timeZone})` : "no - a person closes each day"}</dd>
          {sc.enabled && <><dt>Next close due</dt><dd>{when(sc.nextCutOff)}</dd></>}
          <dt>Alert if a run takes longer than</dt><dd>{sc.overrunMinutes} minutes</dd>
          <dt>Retries after a failure</dt><dd>{sc.maxAttempts} attempt(s), {sc.retryMinutes} minutes apart</dd>
          <dt>Missed days closed automatically</dt><dd>up to {sc.maxCatchUpDays}</dd>
          <dt>Last changed</dt><dd>{sc.updatedBy ? `${sc.updatedBy}, ${when(sc.updatedAt)}` : "never (default)"}</dd>
        </dl>
      )}
      {form && (
        <div className="form">
          <label><input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} /> Close the day automatically</label>
          <label>Time of day (24h, tenant time zone)<input type="time" value={form.runAt} onChange={(e) => setForm({ ...form, runAt: e.target.value })} /></label>
          <label>Alert after (minutes)<input type="number" min={5} max={720} value={form.overrunMinutes} onChange={num("overrunMinutes")} /></label>
          <label>Attempts after a failure<input type="number" min={1} max={10} value={form.maxAttempts} onChange={num("maxAttempts")} /></label>
          <label>Minutes between attempts<input type="number" min={5} max={240} value={form.retryMinutes} onChange={num("retryMinutes")} /></label>
          <label>Missed days to catch up<input type="number" min={0} max={7} value={form.maxCatchUpDays} onChange={num("maxCatchUpDays")} /></label>
          <p className="wide">A time before noon closes the day in the early hours after it (01:00 closes Monday at 01:00 on Tuesday).</p>
          <ProblemBox problem={action.error} />
          <div className="actions">
            <button type="button" disabled={action.busy} onClick={() => void save()}>{action.busy ? "Saving..." : "Save schedule"}</button>
            <button type="button" onClick={() => setForm(null)}>Cancel</button>
          </div>
        </div>
      )}
    </Section>
  );
}
