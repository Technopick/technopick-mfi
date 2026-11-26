import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { actorOf, hasAnyRole } from "../auth/session";
import { Empty, Loading, OwnItemNote, ProblemBox, Section, Status, when } from "../components/ui";
import { downloadCsv } from "../csv";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Product = Schemas["FixedDepositProductView"];
type Detail = Schemas["FixedDepositDetail"];
type Member = Schemas["MemberFixedDeposits"];
type Early = Schemas["EarlyWithdrawalView"];
type Portfolio = Schemas["FixedDepositPortfolio"];

const BASE = "/api/v1/fixed-deposits";
const INSTRUCTION: Record<string, string> = {
  PAYOUT: "Pay principal and interest to savings",
  ROLLOVER_PRINCIPAL: "Roll over the principal, pay interest to savings",
  ROLLOVER_ALL: "Roll over principal and interest",
};

function InstructionSelect({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  return (
    <label>{label}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {Object.entries(INSTRUCTION).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
      </select>
    </label>
  );
}

function PlaceForm({ member, products, onDone }: { member: Member; products: Product[]; onDone: (d: Detail) => void }) {
  const api = useApi();
  const action = useAction();
  const active = products.filter((p) => p.status === "ACTIVE");
  const [product, setProduct] = useState(active[0]?.productCode ?? "");
  const [account, setAccount] = useState("");
  const [amount, setAmount] = useState("");
  const [instruction, setInstruction] = useState("PAYOUT");
  const [key, setKey] = useState(newIdempotencyKey);
  const chosen = active.find((p) => p.productCode === product);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const d = await action.run(() => api.post<Detail>(`${BASE}/deposits`, {
      idempotencyKey: key, customerNumber: member.customerNumber, accountNumber: account.trim(), productCode: product,
      amount: amount.trim(), maturityInstruction: instruction,
    }));
    if (d) {
      setKey(newIdempotencyKey());
      setAmount("");
      onDone(d);
    }
  }
  if (!active.length) return <p className="warning">No fixed deposit product is open; finance sets them up below.</p>;
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Place a fixed deposit from savings</h3>
      <ProblemBox problem={action.error} />
      <label>Product
        <select value={product} onChange={(e) => setProduct(e.target.value)}>
          {active.map((p) => <option key={p.productCode} value={p.productCode}>{p.name} ({p.termMonths} months, {p.ratePct}%)</option>)}
        </select>
      </label>
      {chosen && <p className="muted small">{formatAmount(chosen.minAmount)} to {chosen.maxAmount ? formatAmount(chosen.maxAmount) : "any amount"};
        {" "}{chosen.earlyWithdrawalAllowed ? `early withdrawal earns ${chosen.earlyRatePct}%` : "no early withdrawal"};
        {" "}withholding tax {chosen.taxRatePct}%.</p>}
      <label>Pay from the member's savings account<input value={account} required pattern="[0-9]{10}" inputMode="numeric"
        onChange={(e) => setAccount(e.target.value)} /></label>
      <label>Amount to deposit<input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} /></label>
      <InstructionSelect label="At maturity" value={instruction} onChange={setInstruction} />
      <div className="actions"><button type="submit" disabled={action.busy}>Place deposit</button></div>
    </form>
  );
}

function Quote({ title, q }: { title: string; q: Schemas["FixedDepositQuote"] | null | undefined }) {
  if (!q) return null;
  return (
    <div><dt>{title}</dt><dd>{formatAmount(q.interest)} interest for {q.nights} nights at {q.ratePct}%, tax {formatAmount(q.tax)};
      {" "}pays {formatAmount(q.total)}</dd></div>
  );
}

function DepositPanel({ number, canChange, canAskEarly, onChanged }:
  { number: string; canChange: boolean; canAskEarly: boolean; onChanged: (message: string) => void }) {
  const api = useApi();
  const detail = useLoad<Detail>(`${BASE}/deposits/${encodeURIComponent(number)}`);
  const change = useAction();
  const early = useAction();
  const [instruction, setInstruction] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const d = detail.data;
  if (detail.loading && !d) return <Loading />;
  const dep = d?.deposit;
  if (!d || !dep) return <ProblemBox problem={detail.error} />;
  const fd = dep;
  const active = dep.status === "ACTIVE";
  async function saveInstruction() {
    const r = await change.run(() => api.put<Detail>(`${BASE}/deposits/${fd.depositNumber}/instruction`,
      { maturityInstruction: instruction ?? fd.maturityInstruction }));
    if (r) {
      detail.reload();
      onChanged(`${fd.depositNumber} will ${INSTRUCTION[r.deposit?.maturityInstruction ?? ""]?.toLowerCase()} at maturity.`);
    }
  }
  async function askEarly(e: FormEvent) {
    e.preventDefault();
    const r = await early.run(() => api.post<Early>(`${BASE}/deposits/${fd.depositNumber}/early-withdrawals`,
      { idempotencyKey: key, reason: reason.trim() }));
    if (r) {
      setKey(newIdempotencyKey());
      setReason("");
      detail.reload();
      onChanged(`Early withdrawal of ${fd.depositNumber} asked for; a branch manager or supervisor must approve it.`);
    }
  }
  return (
    <Section title={`Fixed deposit ${dep.depositNumber}`}>
      <dl className="facts">
        <div><dt>Member</dt><dd>{dep.customerNumber} - {dep.memberName}</dd></div>
        <div><dt>Status</dt><dd><Status value={dep.status} /></dd></div>
        <div><dt>Principal</dt><dd>{formatAmount(dep.principal)}</dd></div>
        <div><dt>Term</dt><dd>{dep.startDate} to {dep.maturityDate} ({dep.termMonths} months at {dep.ratePct}%)</dd></div>
        <div><dt>Savings account</dt><dd>{dep.accountNumber}</dd></div>
        {active && <div><dt>Interest accrued so far</dt><dd>{formatAmount(dep.accruedInterest)}</dd></div>}
        {dep.rolledFromNumber && <div><dt>Rolled over from</dt><dd>{dep.rolledFromNumber}</dd></div>}
        {active && <Quote title="At maturity" q={d.atMaturity} />}
        {active && <Quote title="If withdrawn today" q={d.earlyToday} />}
      </dl>
      {d.closure && (
        <dl className="facts">
          <div><dt>Closed</dt><dd>{d.closure.kind === "EARLY" ? "Withdrawn early" : "Matured"} on {d.closure.businessDate}</dd></div>
          <div><dt>Interest earned</dt><dd>{formatAmount(d.closure.interestEarned)} ({d.closure.nights} nights at {d.closure.rateApplied}%)</dd></div>
          <div><dt>Tax withheld</dt><dd>{formatAmount(d.closure.tax)}</dd></div>
          <div><dt>Paid to savings</dt><dd>{formatAmount(d.closure.toSavings)}</dd></div>
          {d.closure.newDepositNumber && <div><dt>Rolled over into</dt><dd>{d.closure.newDepositNumber} ({formatAmount(d.closure.newPrincipal)})</dd></div>}
        </dl>
      )}
      {active && canChange && (
        <div className="form">
          <ProblemBox problem={change.error} />
          <InstructionSelect label="At maturity" value={instruction ?? dep.maturityInstruction ?? "PAYOUT"} onChange={setInstruction} />
          <div className="actions"><button type="button" className="secondary" disabled={change.busy
            || (instruction ?? dep.maturityInstruction) === dep.maturityInstruction} onClick={() => void saveInstruction()}>
            Save maturity instruction</button></div>
        </div>
      )}
      {active && d.openRequest && <p className="warning">Early withdrawal asked for by {d.openRequest.requestedBy}; waiting for a decision.</p>}
      {active && canAskEarly && d.earlyToday && !d.openRequest && (
        <form className="form" onSubmit={(e) => void askEarly(e)}>
          <h3>Withdraw early</h3>
          <ProblemBox problem={early.error} />
          <label>Reason (at least 10 characters)<input value={reason} required minLength={10} maxLength={300}
            onChange={(e) => setReason(e.target.value)} /></label>
          <div className="actions"><button type="submit" disabled={early.busy}>Ask to withdraw early</button></div>
        </form>
      )}
    </Section>
  );
}

function EarlyDecision({ request, onDone }: { request: Early; onDone: () => void }) {
  const api = useApi();
  const { session } = useAuth();
  const action = useAction();
  const [note, setNote] = useState("");
  if (session && request.requestedBy === actorOf(session)) return <OwnItemNote />;
  async function decide(kind: "approve" | "reject") {
    if (await action.run(() => api.post<Early>(`${BASE}/early-withdrawals/${request.requestId}/${kind}`,
      { reason: note.trim() || undefined }))) onDone();
  }
  return (
    <span className="row-actions">
      <input aria-label={`Note for ${request.depositNumber}`} placeholder="note (needed to reject)" value={note}
        onChange={(e) => setNote(e.target.value)} />
      <button type="button" disabled={action.busy} onClick={() => void decide("approve")}>Approve and pay</button>
      <button type="button" className="secondary" disabled={action.busy || note.trim().length < 3} onClick={() => void decide("reject")}>Reject</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

function EarlySection({ canDecide }: { canDecide: boolean }) {
  const requests = useLoad<Early[]>(`${BASE}/early-withdrawals`);
  return (
    <Section title="Early withdrawals">
      <ProblemBox problem={requests.error} />
      {requests.loading && !requests.data ? <Loading /> : !requests.data?.length ? <Empty>No early withdrawals asked for.</Empty> : (
        <table>
          <thead><tr><th>Deposit</th><th>Member</th><th className="num">Principal</th><th>Pays today</th><th>Reason</th><th>Status</th>
            <th>Asked</th><th /></tr></thead>
          <tbody>{requests.data.map((r) => (
            <tr key={r.requestId}>
              <td>{r.depositNumber}<div className="muted small">matures {r.maturityDate}</div></td>
              <td>{r.customerNumber}<div className="muted small">{r.memberName}</div></td>
              <td className="num">{formatAmount(r.principal)}</td>
              <td>{r.quoteToday ? <>{formatAmount(r.quoteToday.total)}<div className="muted small">interest {formatAmount(r.quoteToday.interest)}
                {" "}at {r.quoteToday.ratePct}%</div></> : ""}</td>
              <td>{r.reason}{r.decisionReason && <div className="muted small">Decision: {r.decisionReason}</div>}</td>
              <td><Status value={r.status} />{r.decidedBy && <div className="muted small">{r.decidedBy}</div>}</td>
              <td>{r.requestedBy}<div className="muted small">{when(r.requestedAt)}</div></td>
              <td>{r.status === "REQUESTED" && canDecide && <EarlyDecision request={r} onDone={() => requests.reload()} />}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </Section>
  );
}

function PortfolioSection() {
  const portfolio = useLoad<Portfolio>(`${BASE}/portfolio?days=30`);
  const p = portfolio.data;
  return (
    <Section title="Fixed deposits held" actions={p?.maturingSoon?.length ? (
      <button type="button" className="secondary" onClick={() => downloadCsv("fixed-deposits-maturing.csv", [
        ["Deposit", "Member", "Name", "Principal", "Rate", "Maturity date", "At maturity", "Accrued"],
        ...(p.maturingSoon ?? []).map((d) => [d.depositNumber, d.customerNumber, d.memberName, d.principal, d.ratePct,
          d.maturityDate, d.maturityInstruction, d.accruedInterest]),
      ])}>Download maturing</button>) : undefined}>
      <ProblemBox problem={portfolio.error} />
      {portfolio.loading && !p ? <Loading /> : p && (
        <>
          <dl className="facts">
            <div><dt>Active deposits</dt><dd>{p.active}</dd></div>
            <div><dt>Principal held</dt><dd>{formatAmount(p.principal)}</dd></div>
            <div><dt>Interest accrued</dt><dd>{formatAmount(p.accruedInterest)}</dd></div>
          </dl>
          <h3>Maturing in the next 30 days</h3>
          {!p.maturingSoon?.length ? <Empty>None.</Empty> : (
            <table>
              <thead><tr><th>Deposit</th><th>Member</th><th className="num">Principal</th><th>Matures</th><th>At maturity</th></tr></thead>
              <tbody>{p.maturingSoon.map((d) => (
                <tr key={d.depositId}><td>{d.depositNumber}</td><td>{d.customerNumber} - {d.memberName}</td>
                  <td className="num">{formatAmount(d.principal)}</td><td>{d.maturityDate}</td>
                  <td>{INSTRUCTION[d.maturityInstruction ?? ""] ?? d.maturityInstruction}</td></tr>
              ))}</tbody>
            </table>
          )}
        </>
      )}
    </Section>
  );
}

function ProductForm({ onSaved }: { onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const [f, setF] = useState({ code: "", name: "", termMonths: "12", ratePct: "", earlyRatePct: "0", earlyWithdrawalAllowed: true,
    minAmount: "1000", maxAmount: "", taxRatePct: "15", principalAccount: "", interestPayableAccount: "", interestExpenseAccount: "",
    taxAccount: "", status: "ACTIVE" });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  async function save(e: FormEvent) {
    e.preventDefault();
    const { code, ...rest } = f;
    if (await action.run(() => api.put<Product>(`${BASE}/products/${encodeURIComponent(code.trim().toUpperCase())}`, {
      ...rest, termMonths: Number(f.termMonths), maxAmount: f.maxAmount.trim() || undefined,
    }))) onSaved();
  }
  return (
    <form className="form" onSubmit={(e) => void save(e)}>
      <h3>Create or change a product</h3>
      <ProblemBox problem={action.error} />
      <label>Product code<input value={f.code} required pattern="[A-Za-z0-9][A-Za-z0-9-]{1,31}" onChange={set("code")} /></label>
      <label>Name<input value={f.name} required minLength={3} onChange={set("name")} /></label>
      <label>Term (months)<input type="number" min={1} max={120} value={f.termMonths} required onChange={set("termMonths")} /></label>
      <label>Annual rate (%)<input value={f.ratePct} required inputMode="decimal" onChange={set("ratePct")} /></label>
      <label>Rate if withdrawn early (%)<input value={f.earlyRatePct} required inputMode="decimal" onChange={set("earlyRatePct")} /></label>
      <label className="check"><input type="checkbox" checked={f.earlyWithdrawalAllowed}
        onChange={(e) => setF({ ...f, earlyWithdrawalAllowed: e.target.checked })} /> Early withdrawal allowed</label>
      <label>Minimum amount<input value={f.minAmount} required inputMode="decimal" onChange={set("minAmount")} /></label>
      <label>Maximum amount (blank for none)<input value={f.maxAmount} inputMode="decimal" onChange={set("maxAmount")} /></label>
      <label>Withholding tax on interest (%)<input value={f.taxRatePct} required inputMode="decimal" onChange={set("taxRatePct")} /></label>
      <label>Deposits account (liability)<input value={f.principalAccount} required onChange={set("principalAccount")} /></label>
      <label>Interest payable (liability)<input value={f.interestPayableAccount} required onChange={set("interestPayableAccount")} /></label>
      <label>Interest expense<input value={f.interestExpenseAccount} required onChange={set("interestExpenseAccount")} /></label>
      <label>Withholding tax payable (liability)<input value={f.taxAccount} required onChange={set("taxAccount")} /></label>
      <label>Status<select value={f.status} onChange={set("status")}><option value="ACTIVE">Open for new deposits</option>
        <option value="RETIRED">Retired</option></select></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Save product</button></div>
      <p className="muted small">Rates and limits apply to new deposits and rollovers only. A product's accounts never change.
        Rates and the tax rate need finance sign-off.</p>
    </form>
  );
}

function ProductsSection({ products, isFinance, onSaved }: { products: Product[]; isFinance: boolean; onSaved: () => void }) {
  return (
    <Section title="Fixed deposit products">
      {!products.length ? <Empty>No fixed deposit products yet.</Empty> : (
        <table>
          <thead><tr><th>Product</th><th>Term</th><th>Rate</th><th>Early</th><th>Amounts</th><th>Tax</th><th>Status</th>
            <th className="num">Active</th></tr></thead>
          <tbody>{products.map((p) => (
            <tr key={p.productCode}>
              <td>{p.productCode}<div className="muted small">{p.name}</div></td><td>{p.termMonths} months</td><td>{p.ratePct}%</td>
              <td>{p.earlyWithdrawalAllowed ? `${p.earlyRatePct}%` : "not allowed"}</td>
              <td>{formatAmount(p.minAmount)} to {p.maxAmount ? formatAmount(p.maxAmount) : "any"}</td><td>{p.taxRatePct}%</td>
              <td><Status value={p.status} /></td><td className="num">{p.activeDeposits}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {isFinance && <ProductForm onSaved={onSaved} />}
    </Section>
  );
}

/** Fixed deposits: a member's deposits, placing one, maturity instructions, early withdrawals, products (ADR-0041). */
export function FixedDepositsPage() {
  const api = useApi();
  const { session } = useAuth();
  const products = useLoad<Product[]>(`${BASE}/products`);
  const lookup = useAction();
  const [number, setNumber] = useState("");
  const [member, setMember] = useState<Member | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const canPlace = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER");
  const canAskEarly = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS");
  const seesEarly = hasAnyRole(session, "TELLER", "LOAN_OFFICER", "BRANCH_MANAGER", "OPERATIONS", "SUPERVISOR");
  const seesPortfolio = hasAnyRole(session, "FINANCE", "SUPERVISOR", "BRANCH_MANAGER", "OPERATIONS");
  const isFinance = hasAnyRole(session, "FINANCE");

  async function load(customerNumber: string) {
    const m = await lookup.run(() => api.get<Member>(`${BASE}/members/${encodeURIComponent(customerNumber)}`));
    setMember(m ?? null);
  }
  async function find(e: FormEvent) {
    e.preventDefault();
    setNotice(null);
    setSelected(null);
    await load(number.trim().toUpperCase());
  }

  return (
    <>
      <h1>Fixed deposits</h1>
      {notice && <p className="ok" role="status">{notice}</p>}
      <Section title="Find a member">
        <form className="form" onSubmit={(e) => void find(e)}>
          <label>Member number<input value={number} required placeholder="CU26-0000001" onChange={(e) => setNumber(e.target.value)} /></label>
          <div className="actions"><button type="submit" disabled={lookup.busy}>Show deposits</button></div>
        </form>
        <ProblemBox problem={lookup.error} />
        {member && (
          <>
            <p>{member.customerNumber} - {member.name}: {formatAmount(member.activePrincipal)} in active fixed deposits.</p>
            {!member.deposits?.length ? <Empty>No fixed deposits yet.</Empty> : (
              <table>
                <thead><tr><th>Deposit</th><th>Product</th><th className="num">Principal</th><th>Rate</th><th>Start</th><th>Matures</th>
                  <th>Status</th></tr></thead>
                <tbody>{member.deposits.map((d) => (
                  <tr key={d.depositId}>
                    <td><button type="button" className="link" onClick={() => setSelected(d.depositNumber ?? null)}>{d.depositNumber}</button></td>
                    <td>{d.productCode}</td><td className="num">{formatAmount(d.principal)}</td><td>{d.ratePct}%</td>
                    <td>{d.startDate}</td><td>{d.maturityDate}</td><td><Status value={d.status} /></td>
                  </tr>
                ))}</tbody>
              </table>
            )}
            {canPlace && products.data && <PlaceForm member={member} products={products.data} onDone={(d) => {
              setNotice(`${d.deposit?.depositNumber} placed: ${formatAmount(d.deposit?.principal)} until ${d.deposit?.maturityDate}, `
                + `paying ${formatAmount(d.atMaturity?.total)} at maturity.`);
              setSelected(d.deposit?.depositNumber ?? null);
              void load(member.customerNumber ?? "");
            }} />}
          </>
        )}
      </Section>
      {selected && <DepositPanel key={`${selected}-${tick}`} number={selected} canChange={canPlace} canAskEarly={canAskEarly}
        onChanged={(message) => {
          setNotice(message);
          setTick((n) => n + 1);
        }} />}
      {seesEarly && <EarlySection key={tick} canDecide={hasAnyRole(session, "BRANCH_MANAGER", "SUPERVISOR")} />}
      {seesPortfolio && <PortfolioSection />}
      <ProblemBox problem={products.error} />
      {products.data && <ProductsSection products={products.data} isFinance={isFinance} onSaved={() => products.reload()} />}
    </>
  );
}
