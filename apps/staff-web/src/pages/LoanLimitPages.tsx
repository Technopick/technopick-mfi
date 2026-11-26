import { useState, type FormEvent } from "react";
import { useApi } from "../api/ApiContext";
import { newIdempotencyKey, type Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";
import { formatAmount } from "../money";

type Cover = Schemas["ApplicationCover"];
type Guarantee = Schemas["GuaranteeView"];
type Rule = Schemas["LoanLimitRuleView"];

const L = "/api/v1/lending";

function WithdrawButton({ guarantee, onDone }: { guarantee: Guarantee; onDone: () => void }) {
  const api = useApi();
  const action = useAction();
  const [reason, setReason] = useState("");
  return (
    <span className="row-actions">
      <input aria-label={`Reason to withdraw ${guarantee.guarantorNumber}'s pledge`} placeholder="reason" value={reason}
        onChange={(e) => setReason(e.target.value)} />
      <button type="button" className="secondary" disabled={action.busy || reason.trim().length < 3} onClick={() => void (async () => {
        if (await action.run(() => api.post<Cover>(`${L}/guarantees/${guarantee.guaranteeId}/withdraw`, { reason: reason.trim() }))) onDone();
      })()}>Withdraw pledge</button>
      <ProblemBox problem={action.error} />
    </span>
  );
}

function PledgeForm({ applicationId, onDone }: { applicationId: string; onDone: () => void }) {
  const api = useApi();
  const action = useAction();
  const [guarantor, setGuarantor] = useState("");
  const [account, setAccount] = useState("");
  const [amount, setAmount] = useState("");
  const [consent, setConsent] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await action.run(() => api.post<Cover>(`${L}/applications/${applicationId}/guarantees`, {
      idempotencyKey: key, guarantorNumber: guarantor.trim().toUpperCase(), accountNumber: account.trim(), amount: amount.trim(),
      consentReference: consent.trim(),
    }))) {
      setKey(newIdempotencyKey());
      setGuarantor("");
      setAccount("");
      setAmount("");
      setConsent("");
      onDone();
    }
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)}>
      <h3>Add a guarantor</h3>
      <ProblemBox problem={action.error} />
      <label>Guarantor member number<input value={guarantor} required placeholder="CU26-0000001" onChange={(e) => setGuarantor(e.target.value)} /></label>
      <label>Guarantor's savings account<input value={account} required pattern="[0-9]{10}" inputMode="numeric"
        onChange={(e) => setAccount(e.target.value)} /></label>
      <label>Amount guaranteed<input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} /></label>
      <label>Signed consent reference<input value={consent} required minLength={3} placeholder="guarantee form number"
        onChange={(e) => setConsent(e.target.value)} /></label>
      <div className="actions"><button type="submit" disabled={action.busy}>Pledge guarantee</button></div>
      <p className="muted small">The amount is locked on the guarantor's savings until the loan is repaid, or released if the application
        is declined or withdrawn.</p>
    </form>
  );
}

/** The application's limit and its guarantors (ADR-0043), shown on the application page. */
export function ApplicationCoverPanel({ applicationId, applicationStatus }: { applicationId: string; applicationStatus: string }) {
  const { session } = useAuth();
  const cover = useLoad<Cover>(`${L}/applications/${applicationId}/cover`);
  const c = cover.data;
  const open = applicationStatus === "SUBMITTED";
  const canPledge = open && hasAnyRole(session, "LOAN_OFFICER", "BRANCH_MANAGER");
  const reload = () => cover.reload();
  if (cover.loading && !c) return <Loading />;
  if (!c) return <ProblemBox problem={cover.error} />;
  const l = c.limit;
  return (
    <Section title="Limit and guarantors">
      {!l?.ruleApplies ? <p className="muted">This product has no loan limit rule.</p> : (
        <dl className="facts">
          <div><dt>Own cover</dt><dd>{formatAmount(l.ownCover)}<div className="muted small">savings {formatAmount(l.savings)}
            {Number(l.pledgedForOthers) > 0 ? `, less ${formatAmount(l.pledgedForOthers)} pledged for others` : ""}
            {Number(l.shares) > 0 ? `, plus ${formatAmount(l.shares)} shares` : ""}</div></dd></div>
          <div><dt>Limit</dt><dd>{formatAmount(l.limit)}<div className="muted small">{l.savingsMultiple} x own cover, less
            {" "}{formatAmount(l.outstanding)} outstanding</div></dd></div>
          <div><dt>Asked for</dt><dd>{formatAmount(c.principal)} {c.withinLimit ? "" : <strong className="warning">above the limit</strong>}</dd></div>
          {l.guaranteesRequired && <div><dt>Guarantees needed</dt><dd>{formatAmount(c.guaranteeNeeded)}; pledged {formatAmount(c.pledged)}
            {" "}{c.covered ? <Status value="COVERED" /> : <strong className="warning">not yet covered</strong>}</dd></div>}
        </dl>
      )}
      {!c.guarantees?.length ? <Empty>No guarantors yet.</Empty> : (
        <table>
          <thead><tr><th>Guarantor</th><th>Account</th><th className="num">Amount</th><th>Consent</th><th>Status</th><th>Pledged</th><th /></tr></thead>
          <tbody>{c.guarantees.map((g) => (
            <tr key={g.guaranteeId}>
              <td>{g.guarantorNumber}<div className="muted small">{g.guarantorName}</div></td><td>{g.accountNumber}</td>
              <td className="num">{formatAmount(g.amount)}</td><td>{g.consentReference}</td>
              <td><Status value={g.status} />{g.endReason && <div className="muted small">{g.endReason}</div>}</td>
              <td>{g.pledgedBy}<div className="muted small">{when(g.pledgedAt)}</div></td>
              <td>{canPledge && g.status === "PLEDGED" && <WithdrawButton guarantee={g} onDone={reload} />}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {canPledge && <PledgeForm applicationId={applicationId} onDone={reload} />}
    </Section>
  );
}

function RuleRow({ rule, canEdit, onSaved }: { rule: Rule; canEdit: boolean; onSaved: () => void }) {
  const api = useApi();
  const action = useAction();
  const [multiple, setMultiple] = useState(rule.savingsMultiple ?? "3.00");
  const [shares, setShares] = useState(rule.savingsMultiple ? !!rule.includeShares : true);
  const [deduct, setDeduct] = useState(rule.savingsMultiple ? !!rule.deductOutstanding : true);
  const [guarantees, setGuarantees] = useState(!!rule.guaranteesRequired);
  async function save() {
    if (await action.run(() => api.put<Rule>(`${L}/limit-rules/${rule.productCode}`, {
      savingsMultiple: multiple.trim(), includeShares: shares, deductOutstanding: deduct, guaranteesRequired: guarantees,
    }))) onSaved();
  }
  async function remove() {
    if (await action.run(() => api.post<Rule[]>(`${L}/limit-rules/${rule.productCode}/remove`))) onSaved();
  }
  const has = !!rule.savingsMultiple;
  if (!canEdit) {
    return (
      <tr><td>{rule.productCode}<div className="muted small">{rule.productName}</div></td>
        <td>{has ? `${rule.savingsMultiple} x` : "no limit"}</td><td>{has ? (rule.includeShares ? "yes" : "no") : ""}</td>
        <td>{has ? (rule.deductOutstanding ? "yes" : "no") : ""}</td><td>{has ? (rule.guaranteesRequired ? "yes" : "no") : ""}</td><td /></tr>
    );
  }
  return (
    <tr>
      <td>{rule.productCode}<div className="muted small">{rule.productName}</div>
        {rule.updatedBy && <div className="muted small">{rule.updatedBy} {when(rule.updatedAt)}</div>}</td>
      <td><input aria-label={`Multiple of own cover for ${rule.productCode}`} value={multiple} inputMode="decimal" size={5}
        onChange={(e) => setMultiple(e.target.value)} /></td>
      <td><input type="checkbox" aria-label={`Count shares for ${rule.productCode}`} checked={shares} onChange={(e) => setShares(e.target.checked)} /></td>
      <td><input type="checkbox" aria-label={`Deduct other loans for ${rule.productCode}`} checked={deduct} onChange={(e) => setDeduct(e.target.checked)} /></td>
      <td><input type="checkbox" aria-label={`Require guarantees for ${rule.productCode}`} checked={guarantees}
        onChange={(e) => setGuarantees(e.target.checked)} /></td>
      <td>
        <button type="button" disabled={action.busy} onClick={() => void save()}>{has ? "Save" : "Set limit"}</button>
        {has && <button type="button" className="secondary" disabled={action.busy} onClick={() => void remove()}>Remove limit</button>}
        <ProblemBox problem={action.error} />
      </td>
    </tr>
  );
}

/** Loan limit rules per product (ADR-0043); finance changes them, others read them. */
export function LoanLimitsPage() {
  const { session } = useAuth();
  const rules = useLoad<Rule[]>(`${L}/limit-rules`);
  const canEdit = hasAnyRole(session, "FINANCE");
  return (
    <>
      <h1>Loan limits</h1>
      <p className="muted">A member may borrow up to a multiple of their own cover: savings, less what they have pledged for other members,
        plus shares if counted; less their other loans' outstanding principal if deducted. Where guarantees are required, guarantors must
        cover the part of the loan above the member's own cover. Limits are checked when an application is submitted and again at approval.</p>
      <Section title="Rules by product">
        <ProblemBox problem={rules.error} />
        {rules.loading && !rules.data ? <Loading /> : !rules.data?.length ? <Empty>No loan products.</Empty> : (
          <table>
            <thead><tr><th>Product</th><th>Multiple of own cover</th><th>Count shares</th><th>Deduct other loans</th>
              <th>Guarantees above own cover</th><th /></tr></thead>
            <tbody>{rules.data.map((r) => (
              <RuleRow key={`${r.productCode}-${r.updatedAt ?? "none"}`} rule={r} canEdit={canEdit} onSaved={() => rules.reload()} />
            ))}</tbody>
          </table>
        )}
        <p className="muted small">The multiples and the guarantee rule are credit policy decisions for the board.</p>
      </Section>
    </>
  );
}
