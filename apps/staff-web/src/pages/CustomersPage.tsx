import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { CustomerAccounts } from "./SavingsPages";

type Summary = Schemas["CustomerSummary"];
type Detail = Schemas["CustomerDetail"];

/** Consent wording shown to the customer; its version is recorded by the API (CustomerService.CONSENT_VERSION). */
export const CONSENT_TEXT =
  "I agree that the institution may collect and process my personal data (identity, contact and financial " +
  "information) to open and manage my accounts and loans, to meet legal and regulatory obligations, and to share it " +
  "with credit reference bureaus and M-Pesa as needed for these purposes, under the Kenya Data Protection Act, 2019.";

export function CustomersPage() {
  const { session } = useAuth();
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  const canVerify = hasAnyRole(session, "BRANCH_MANAGER", "OPERATIONS");
  const pending = useLoad<Summary[]>(canVerify ? "/api/v1/customers?status=PENDING_VERIFICATION" : null);
  const results = useLoad<Summary[]>(submitted ? `/api/v1/customers?q=${encodeURIComponent(submitted)}` : null);

  function search(e: FormEvent) {
    e.preventDefault();
    setSubmitted(query.trim());
  }

  return (
    <>
      <h1>Customers</h1>
      <form onSubmit={search} className="search">
        <label>Customer number, ID number, phone or name<input value={query} onChange={(e) => setQuery(e.target.value)} /></label>
        <button type="submit" disabled={query.trim().length < 3}>Search</button>
        {hasAnyRole(session, "LOAN_OFFICER", "TELLER") && <Link className="button" to="/customers/new">Register customer</Link>}
      </form>
      {submitted && (
        <Section title="Search results">
          <ProblemBox problem={results.error} />
          {results.loading ? <Loading /> : !results.data?.length ? <Empty>No customer matches.</Empty> : <CustomerTable rows={results.data} />}
        </Section>
      )}
      {canVerify && (
        <Section title="Awaiting KYC verification">
          <ProblemBox problem={pending.error} />
          {pending.loading ? <Loading /> : !pending.data?.length ? <Empty>No customers are waiting.</Empty> : <CustomerTable rows={pending.data} />}
        </Section>
      )}
    </>
  );
}

function CustomerTable({ rows }: { rows: Summary[] }) {
  return (
    <table>
      <thead><tr><th>Number</th><th>Name</th><th>ID</th><th>Phone</th><th>Branch</th><th>Status</th></tr></thead>
      <tbody>
        {rows.map((c) => (
          <tr key={c.customerNumber}>
            <td><Link to={`/customers/${c.customerNumber}`}>{c.customerNumber}</Link></td>
            <td>{c.fullName}</td>
            <td>{(c.idType ?? "").replace(/_/g, " ").toLowerCase()} {c.idNumberMasked}</td>
            <td>{c.phoneMasked}</td>
            <td>{c.branchCode}</td>
            <td><Status value={c.status} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function NewCustomerPage() {
  const api = useApi();
  const navigate = useNavigate();
  const { busy, error, run } = useAction();
  const [key] = useState(newIdempotencyKey);
  const [f, setF] = useState({
    firstName: "", middleName: "", lastName: "", dateOfBirth: "", idType: "NATIONAL_ID", idNumber: "", kraPin: "",
    phoneNumber: "", email: "", branchCode: "", consentGiven: false,
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await run(() => api.post<Schemas["CustomerRegistered"]>("/api/v1/customers", {
      idempotencyKey: key,
      firstName: f.firstName.trim(), middleName: f.middleName.trim() || undefined, lastName: f.lastName.trim(),
      dateOfBirth: f.dateOfBirth, idType: f.idType, idNumber: f.idNumber.trim(), kraPin: f.kraPin.trim() || undefined,
      phoneNumber: f.phoneNumber.trim(), email: f.email.trim() || undefined, branchCode: f.branchCode.trim(),
      consentGiven: f.consentGiven,
    }));
    if (r) navigate(`/customers/${r.customerNumber}`);
  }

  return (
    <>
      <h1>Register customer</h1>
      <Section title="Identity and contact">
        <form onSubmit={submit} className="form">
          <label>First name<input required value={f.firstName} onChange={set("firstName")} /></label>
          <label>Middle name (optional)<input value={f.middleName} onChange={set("middleName")} /></label>
          <label>Last name<input required value={f.lastName} onChange={set("lastName")} /></label>
          <label>Date of birth<input required type="date" value={f.dateOfBirth} onChange={set("dateOfBirth")} /></label>
          <label>ID document
            <select value={f.idType} onChange={set("idType")}>
              <option value="NATIONAL_ID">National ID</option>
              <option value="PASSPORT">Passport</option>
              <option value="ALIEN_ID">Alien ID</option>
            </select>
          </label>
          <label>ID number<input required value={f.idNumber} onChange={set("idNumber")} /></label>
          <label>KRA PIN (optional)<input value={f.kraPin} onChange={set("kraPin")} /></label>
          <label>M-Pesa number<input required value={f.phoneNumber} onChange={set("phoneNumber")} placeholder="07XX XXX XXX" /></label>
          <label>Email (optional)<input type="email" value={f.email} onChange={set("email")} /></label>
          <label>Branch code<input required value={f.branchCode} onChange={set("branchCode")} /></label>
          <div className="wide consent">
            <p>{CONSENT_TEXT}</p>
            <label className="check">
              <input type="checkbox" checked={f.consentGiven} onChange={(e) => setF({ ...f, consentGiven: e.target.checked })} />
              The customer has read (or been read) this statement and agrees
            </label>
          </div>
          <ProblemBox problem={error} />
          <div className="actions">
            <button type="submit" disabled={busy || !f.consentGiven}>Register (KYC verification follows)</button>
          </div>
        </form>
      </Section>
    </>
  );
}

export function CustomerPage() {
  const { number } = useParams();
  const api = useApi();
  const { session } = useAuth();
  const { data, error, loading, reload } = useLoad<Detail>(`/api/v1/customers/${encodeURIComponent(number ?? "")}`);
  const action = useAction();
  const [note, setNote] = useState("");
  if (loading) return <Loading />;
  if (!data) return <ProblemBox problem={error} />;
  const own = session ? data.createdBy === actorOf(session) : false;
  const canVerify = data.status === "PENDING_VERIFICATION" && hasAnyRole(session, "BRANCH_MANAGER", "OPERATIONS");

  async function verify() {
    const done = await action.run(() => api.post(`/api/v1/customers/${data!.customerNumber}/verify`, { note: note.trim() }));
    if (done) reload();
  }

  return (
    <>
      <h1>{data.firstName} {data.middleName ?? ""} {data.lastName}</h1>
      <Section title={data.customerNumber ?? ""} actions={<Status value={data.status} />}>
        <dl className="facts">
          <div><dt>Date of birth</dt><dd>{data.dateOfBirth}</dd></div>
          <div><dt>ID</dt><dd>{(data.idType ?? "").replace(/_/g, " ").toLowerCase()} {data.idNumber}</dd></div>
          {data.kraPin && <div><dt>KRA PIN</dt><dd>{data.kraPin}</dd></div>}
          <div><dt>M-Pesa number</dt><dd>{data.phoneNumber}</dd></div>
          {data.email && <div><dt>Email</dt><dd>{data.email}</dd></div>}
          <div><dt>Branch</dt><dd>{data.branchCode}</dd></div>
          <div><dt>Consent</dt><dd>{data.consentVersion} - {when(data.consentAt)}</dd></div>
          <div><dt>Registered</dt><dd>{data.createdBy} - {when(data.createdAt)}</dd></div>
          {data.verifiedBy && <div><dt>Verified</dt><dd>{data.verifiedBy} - {when(data.verifiedAt)}: {data.verificationNote}</dd></div>}
        </dl>
        {canVerify && (own ? <OwnItemNote /> : (
          <div className="decision">
            <label>What was checked (document sighted, IPRS reference...)<input value={note} onChange={(e) => setNote(e.target.value)} /></label>
            <button type="button" disabled={action.busy || note.trim().length < 10} onClick={() => void verify()}>Verify identity</button>
          </div>
        ))}
        <ProblemBox problem={action.error} />
      </Section>
      {data.customerNumber && (
        <CustomerAccounts customerNumber={data.customerNumber} branchCode={data.branchCode ?? ""} active={data.status === "ACTIVE"} />
      )}
    </>
  );
}
