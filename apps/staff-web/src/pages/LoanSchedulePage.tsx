import { useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router";
import type { Schemas } from "../api/client";
import { Empty, Loading, Money, ProblemBox, Section, Status } from "../components/ui";
import { useLoad } from "../hooks";
import { ReschedulePanel } from "./ReschedulePages";
import { SettlementPanel } from "./SettlementPages";

export function LoanSchedulePage() {
  const { number } = useParams();
  const navigate = useNavigate();
  const [query, setQuery] = useState(number ?? "");
  const { data, error, loading, reload } = useLoad<Schemas["LoanScheduleView"]>(
    number ? `/api/v1/lending/loans/${encodeURIComponent(number)}/schedule` : null,
  );
  function submit(e: FormEvent) {
    e.preventDefault();
    if (query.trim()) navigate(`/loans/${query.trim().toUpperCase()}`);
  }
  return (
    <>
      <h1>Loan schedule</h1>
      <form onSubmit={submit} className="search">
        <label>Loan number<input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="LN26-0000001" /></label>
        <button type="submit">Show</button>
      </form>
      {number && (
        <Section title={`${data?.loanNumber ?? number}`} actions={<Status value={data?.status} />}>
          <ProblemBox problem={error} />
          {data && (data.scheduleVersion ?? 0) > 1 && (
            <p className="muted small">Schedule version {data.scheduleVersion}{data.restructured ? " - this loan has been restructured" : ""}.</p>
          )}
          {loading ? <Loading /> : data && ((data.installments ?? []).length === 0 ? <Empty>No schedule.</Empty> : (
            <table className="compact">
              <thead><tr><th>#</th><th>Due</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Installment</th><th className="num">Balance after</th></tr></thead>
              <tbody>
                {(data.installments ?? []).map((i) => (
                  <tr key={i.number}>
                    <td>{i.number}</td><td>{i.dueDate}</td>
                    <td className="num"><Money amount={i.principal} /></td>
                    <td className="num"><Money amount={i.interest} /></td>
                    <td className="num"><Money amount={i.total} /></td>
                    <td className="num"><Money amount={i.closingBalance} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        </Section>
      )}
      {number && data && <SettlementPanel key={number} loanNumber={number} onChange={reload} />}
      {number && data && data.status === "ACTIVE" && <ReschedulePanel key={`r-${number}`} loanNumber={number} onChange={reload} />}
    </>
  );
}
