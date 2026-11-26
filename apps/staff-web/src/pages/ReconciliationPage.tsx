import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { Schemas } from "../api/client";
import { Empty, Loading, ProblemBox, Section, when } from "../components/ui";
import { useLoad } from "../hooks";

type Reconciliation = Schemas["Reconciliation"];

const LABELS: Record<string, string> = {
  SAVINGS_CONTROL: "Savings balances",
  SAVINGS_INTEREST_PAYABLE: "Savings interest payable",
  LOAN_PRINCIPAL: "Loan principal",
  LOAN_INTEREST_RECEIVABLE: "Loan interest receivable",
  LOAN_PENALTY_RECEIVABLE: "Loan penalties receivable",
  LOAN_OVERPAYMENT: "Loan overpayments held",
  LOAN_INTEREST_SUSPENSE: "Interest in suspense (non-performing loans)",
  LOAN_LOSS_ALLOWANCE: "Loan loss allowance (IFRS 9)",
  SHARE_CAPITAL: "Member share capital",
  TERM_DEPOSIT_PRINCIPAL: "Fixed deposits",
  TERM_DEPOSIT_INTEREST: "Interest payable on fixed deposits",
};

/** Control accounts against the business records: live now, or as recorded by a past end of day. */
export function ReconciliationPage() {
  const [params, setParams] = useSearchParams();
  const [date, setDate] = useState(params.get("businessDate") ?? "");
  const query = params.get("businessDate") ? `?businessDate=${params.get("businessDate")}` : "";
  const rec = useLoad<Reconciliation>(`/api/v1/accounting/reconciliation${query}`);
  const r = rec.data;

  return (
    <>
      <p><Link to="/ledger">Trial balance</Link></p>
      <h1>Reconciliation</h1>
      <form className="search" onSubmit={(e) => { e.preventDefault(); setParams(date ? { businessDate: date } : {}); }}>
        <label>Closed day<input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
        <button type="submit">{date ? "Show recorded" : "Check now"}</button>
      </form>
      <ProblemBox problem={rec.error} />
      {rec.loading && !r ? <Loading /> : r && (
        <Section title={r.recorded ? `Recorded by the end of day of ${r.businessDate}` : `Checked now (${when(r.checkedAt)})`}>
          <p className={r.differences ? "problem" : "ok"} role="status">
            {r.differences ? `${r.differences} control account(s) do not match their records - investigate before relying on the ledger.`
              : "Every control account matches its records."}
          </p>
          {!r.results?.length ? <Empty>{r.recorded ? "Nothing was recorded for that day." : "No control accounts to check."}</Empty> : (
            <table>
              <thead><tr><th>Check</th><th>Account</th><th className="num">Ledger</th><th className="num">Records</th>
                <th className="num">Difference</th><th className="num">Items</th></tr></thead>
              <tbody>
                {r.results.map((c) => (
                  <tr key={`${c.checkCode}-${c.accountCode}`} className={c.reconciled ? undefined : "attention"}>
                    <td>{LABELS[c.checkCode ?? ""] ?? c.checkCode}</td>
                    <td><Link to={`/ledger/accounts/${encodeURIComponent(c.accountCode ?? "")}`}>{c.accountCode}</Link></td>
                    <td className="num">{c.ledgerBalance}</td><td className="num">{c.subledgerBalance}</td>
                    <td className="num">{c.reconciled ? "" : c.difference}</td><td className="num">{c.items}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}
    </>
  );
}
