import { useState } from "react";
import type { Schemas } from "../api/client";
import { Empty, Loading, Money, ProblemBox, Section, Status } from "../components/ui";
import { useLoad } from "../hooks";

type Report = Schemas["PortfolioReport"];

/** Portfolio at risk by branch, loans in arrears and closed loans still holding customer money. */
export function PortfolioPage() {
  const [asOf, setAsOf] = useState("");
  const [query, setQuery] = useState("");
  const report = useLoad<Report>(`/api/v1/reports/portfolio-at-risk${query}`);
  const r = report.data;

  return (
    <>
      <h1>Portfolio at risk</h1>
      <form className="search" onSubmit={(e) => { e.preventDefault(); setQuery(asOf ? `?asOf=${asOf}` : ""); }}>
        <label>As of<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
        <button type="submit">Show</button>
      </form>
      <ProblemBox problem={report.error} />
      {report.loading && !r ? <Loading /> : r && (
        <>
          {!r.asOf ? <Empty>No day has been closed yet, so there are no arrears snapshots.</Empty> : (
            <Section title={`By branch, as of ${r.asOf}`}>
              {!r.branches?.length ? <Empty>No active loans on that day.</Empty> : (
                <table>
                  <thead><tr><th>Branch</th><th className="num">Loans</th><th className="num">Portfolio</th>
                    <th className="num">At risk &gt;1 day</th><th className="num">PAR1</th><th className="num">PAR30</th><th className="num">PAR90</th></tr></thead>
                  <tbody>
                    {r.branches.map((b) => (
                      <tr key={`${b.branchCode}-${b.currency}`}>
                        <td>{b.branchCode}</td>
                        <td className="num">{b.loans}</td>
                        <td className="num"><Money amount={b.portfolio} currency={b.currency} /></td>
                        <td className="num"><Money amount={b.atRisk1} currency={b.currency} /></td>
                        <td className="num">{b.par1}%</td>
                        <td className="num">{b.par30}%</td>
                        <td className="num">{b.par90}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Section>
          )}
          {r.asOf && (
            <Section title="Loans in arrears">
              {!r.loansInArrears?.length ? <Empty>No loan is in arrears.</Empty> : (
                <table>
                  <thead><tr><th>Loan</th><th>Customer</th><th>Branch</th><th className="num">Days past due</th><th>Bucket</th>
                    <th>Classification</th><th className="num">In arrears</th><th className="num">Principal outstanding</th></tr></thead>
                  <tbody>
                    {r.loansInArrears.map((l) => (
                      <tr key={l.loanNumber}>
                        <td>{l.loanNumber}{l.restructured && <div className="muted small">Restructured</div>}</td>
                        <td>{l.customerNumber ?? "-"}</td><td>{l.branchCode}</td>
                        <td className="num">{l.daysPastDue}</td><td>{l.bucket}</td><td><Status value={l.classification} /></td>
                        <td className="num"><Money amount={l.arrearsAmount} /></td>
                        <td className="num"><Money amount={l.outstandingPrincipal} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Section>
          )}
          <Section title="Closed loans still holding customer money">
            {!r.closedWithCredit?.length ? <Empty>None.</Empty> : (
              <>
                <p className="muted small">Overpaid money stays on the loan until a refund policy is agreed; it is not paid out automatically.</p>
                <table>
                  <thead><tr><th>Loan</th><th>Customer</th><th>Branch</th><th>Closed on</th><th className="num">Credit held</th></tr></thead>
                  <tbody>
                    {r.closedWithCredit.map((c) => (
                      <tr key={c.loanNumber}>
                        <td>{c.loanNumber}</td><td>{c.customerNumber ?? "-"}</td><td>{c.branchCode}</td><td>{c.closedOn}</td>
                        <td className="num"><Money amount={c.creditBalance} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </Section>
        </>
      )}
    </>
  );
}
