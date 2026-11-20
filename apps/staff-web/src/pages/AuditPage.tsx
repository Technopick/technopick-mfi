import { useState } from "react";
import { useSearchParams } from "react-router";
import type { Schemas } from "../api/client";
import { Empty, Loading, ProblemBox, Section, when } from "../components/ui";
import { useLoad } from "../hooks";

type Action = Schemas["StaffAction"];
type Chain = Schemas["ChainStatus"];
const BASE = "/api/v1/audit/staff-actions";

/** Who did what: staff changes and reads of customer data, with a check that the trail is intact. */
export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const [actor, setActor] = useState(params.get("actor") ?? "");
  const [path, setPath] = useState(params.get("pathContains") ?? "");
  const [refused, setRefused] = useState(params.get("refusedOnly") === "true");
  const query = params.toString();
  const actions = useLoad<Action[]>(`${BASE}${query ? `?${query}` : ""}`);
  const chain = useLoad<Chain>(`${BASE}/verify`);
  const c = chain.data;

  return (
    <>
      <h1>Audit trail</h1>
      {c && (
        <p role="status" className={c.intact ? "ok" : "problem"}>
          {c.intact ? `Trail intact: all ${c.rows} records verify.`
            : `Trail broken at record ${c.firstBrokenChainNo}: a record was changed or removed outside the application. Report this.`}
        </p>
      )}
      <form className="search" onSubmit={(e) => {
        e.preventDefault();
        const next = new URLSearchParams();
        if (actor.trim()) next.set("actor", actor.trim());
        if (path.trim()) next.set("pathContains", path.trim());
        if (refused) next.set("refusedOnly", "true");
        setParams(next);
      }}>
        <label>Staff member<input value={actor} placeholder="user:..." onChange={(e) => setActor(e.target.value)} /></label>
        <label>Path contains<input value={path} placeholder="e.g. CU26-0000001" onChange={(e) => setPath(e.target.value)} /></label>
        <label><input type="checkbox" checked={refused} onChange={(e) => setRefused(e.target.checked)} /> Refused or failed only</label>
        <button type="submit">Search</button>
      </form>
      <ProblemBox problem={actions.error ?? chain.error} />
      <Section title="Latest 500 matching records">
        {actions.loading && !actions.data ? <Loading /> : !actions.data?.length ? <Empty>No records match.</Empty> : (
          <table className="compact">
            <thead><tr><th>When</th><th>Who</th><th>Roles</th><th>Action</th><th>Path</th><th className="num">Result</th><th>Request</th></tr></thead>
            <tbody>
              {actions.data.map((a) => (
                <tr key={a.chainNo} className={(a.status ?? 0) >= 400 ? "attention" : undefined}>
                  <td>{when(a.occurredAt)}</td><td>{a.actor}</td><td>{a.roles}</td>
                  <td>{a.method} {a.route}</td><td>{a.path}{a.query ? `?${a.query}` : ""}</td>
                  <td className="num">{a.status}</td><td className="muted small">{a.requestId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted small">Recorded: every change, and every read of customer, account, loan or audit data, including refused attempts. Request contents and identifying search terms are not stored.</p>
      </Section>
    </>
  );
}
