import { ApplicationCoverPanel } from "./LoanLimitPages";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, Money, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { isAmount } from "../money";

type View = Schemas["LoanApplicationView"];
type Quote = Schemas["LoanQuote"];

export function ApplicationsPage() {
  const { session } = useAuth();
  const { data, error, loading } = useLoad<View[]>("/api/v1/lending/applications");
  return (
    <>
      <h1>Loan applications</h1>
      <Section
        title="Awaiting decision"
        actions={hasAnyRole(session, "LOAN_OFFICER") && <Link className="button" to="/applications/new">New application</Link>}
      >
        <ProblemBox problem={error} />
        {loading ? <Loading /> : !data?.length ? <Empty>No applications are waiting.</Empty> : (
          <table>
            <thead><tr><th>Customer number</th><th>Product</th><th className="num">Amount</th><th>Term</th><th>Submitted</th><th /></tr></thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.applicationId}>
                  <td>{a.customerRef}</td>
                  <td>{a.productCode}</td>
                  <td className="num"><Money amount={a.principal} currency={a.currency} /></td>
                  <td>{a.termMonths} months</td>
                  <td>{a.submittedBy} - {when(a.submittedAt)}</td>
                  <td><Link to={`/applications/${a.applicationId}`}>Open</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </>
  );
}

export function QuoteTable({ quote }: { quote: Quote }) {
  return (
    <>
      <dl className="facts">
        <div><dt>Interest</dt><dd>{(quote.interestMethod ?? "").replace(/_/g, " ").toLowerCase()} at {quote.annualRate} a year</dd></div>
        <div><dt>Upfront fee</dt><dd><Money amount={quote.upfrontFee} /></dd></div>
        <div><dt>Paid out to customer</dt><dd><Money amount={quote.netDisbursement} /></dd></div>
        <div><dt>Total interest</dt><dd><Money amount={quote.totalInterest} /></dd></div>
        <div><dt>Total to repay</dt><dd><Money amount={quote.totalRepayable} /></dd></div>
      </dl>
      <table className="compact">
        <thead><tr><th>#</th><th>Due</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Installment</th><th className="num">Balance after</th></tr></thead>
        <tbody>
          {(quote.installments ?? []).map((i) => (
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
    </>
  );
}

export function NewApplicationPage() {
  const api = useApi();
  const navigate = useNavigate();
  const { busy, error, run } = useAction();
  const [key] = useState(newIdempotencyKey);
  const [form, setForm] = useState({ customerRef: "", productCode: "", branchCode: "", principal: "", termMonths: "12", firstDueDate: "", purpose: "" });
  const [invalid, setInvalid] = useState<string | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!isAmount(form.principal)) {
      setInvalid("Amount must be a number with at most 2 decimal places, e.g. 50000 or 50000.50");
      return;
    }
    setInvalid(null);
    const created = await run(() =>
      api.post<Schemas["LoanApplicationSubmitted"]>("/api/v1/lending/applications", {
        idempotencyKey: key,
        customerRef: form.customerRef.trim(),
        productCode: form.productCode.trim(),
        branchCode: form.branchCode.trim(),
        principal: form.principal.trim(),
        termMonths: Number.parseInt(form.termMonths, 10),
        firstDueDate: form.firstDueDate || undefined,
        purpose: form.purpose.trim(),
      }),
    );
    if (created) navigate(`/applications/${created.applicationId}`);
  }

  return (
    <>
      <h1>New loan application</h1>
      <Section title="Applicant and terms">
        <form onSubmit={submit} className="form">
          <label>Customer number<input required value={form.customerRef} onChange={set("customerRef")} placeholder="CU26-0000001 (verified customer)" /></label>
          <label>Product code<input required value={form.productCode} onChange={set("productCode")} placeholder="e.g. BIASHARA" /></label>
          <label>Branch code<input required value={form.branchCode} onChange={set("branchCode")} /></label>
          <label>Amount (KES)<input required inputMode="decimal" value={form.principal} onChange={set("principal")} /></label>
          <label>Term (months)<input required type="number" min={1} max={360} value={form.termMonths} onChange={set("termMonths")} /></label>
          <label>First due date (optional)<input type="date" value={form.firstDueDate} onChange={set("firstDueDate")} /></label>
          <label className="wide">Purpose<input required minLength={3} value={form.purpose} onChange={set("purpose")} /></label>
          {invalid && <div className="problem" role="alert">{invalid}</div>}
          <ProblemBox problem={error} />
          <div className="actions"><button type="submit" disabled={busy}>{busy ? "Submitting..." : "Submit for approval"}</button></div>
        </form>
      </Section>
    </>
  );
}

export function ApplicationPage() {
  const { id } = useParams();
  const api = useApi();
  const { session } = useAuth();
  const { data, error, loading, reload } = useLoad<View>(`/api/v1/lending/applications/${id ?? ""}`);
  const action = useAction();
  const [reason, setReason] = useState("");

  if (loading && !data) return <Loading />;
  if (!data) return <ProblemBox problem={error} />;
  const own = session ? data.submittedBy === actorOf(session) : false;
  const canDecide = data.status === "SUBMITTED" && hasAnyRole(session, "BRANCH_MANAGER");

  async function decide(kind: "approve" | "decline") {
    const done = await action.run(() =>
      api.post<View>(`/api/v1/lending/applications/${data!.applicationId}/${kind}`, { reason: reason.trim() || undefined }),
    );
    if (done) reload();
  }

  return (
    <>
      <h1>Application - {data.customerRef}</h1>
      <Section title="Summary" actions={<Status value={data.status} />}>
        <dl className="facts">
          <div><dt>Product</dt><dd>{data.productCode}</dd></div>
          <div><dt>Amount</dt><dd><Money amount={data.principal} currency={data.currency} /></dd></div>
          <div><dt>Term</dt><dd>{data.termMonths} months, first due {data.firstDueDate}</dd></div>
          <div><dt>Branch</dt><dd>{data.branchCode}</dd></div>
          <div><dt>Purpose</dt><dd>{data.purpose}</dd></div>
          <div><dt>Submitted</dt><dd>{data.submittedBy} - {when(data.submittedAt)}</dd></div>
          {data.decidedBy && <div><dt>Decided by</dt><dd>{data.decidedBy}{data.decisionReason ? ` - ${data.decisionReason}` : ""}</dd></div>}
          {data.loanNumber && <div><dt>Loan</dt><dd><Link to={`/loans/${data.loanNumber}`}>{data.loanNumber}</Link></dd></div>}
        </dl>
        {canDecide && (own ? <OwnItemNote /> : (
          <div className="decision">
            <label>Reason (required to decline)<input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
            <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve and create loan</button>
            <button type="button" className="secondary" disabled={action.busy || reason.trim().length < 3} onClick={() => void decide("decline")}>Decline</button>
          </div>
        ))}
        <ProblemBox problem={action.error} />
      </Section>
      {data.applicationId && <ApplicationCoverPanel key={`${data.applicationId}-${data.status}`} applicationId={data.applicationId}
        applicationStatus={data.status ?? ""} />}
      {data.quote && <Section title="Repayment schedule (quote)"><QuoteTable quote={data.quote} /></Section>}
    </>
  );
}
