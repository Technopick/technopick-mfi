import { Link } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { useLoad } from "../hooks";

function Tile({ to, label, path }: { to: string; label: string; path: string }) {
  const { data, error, loading } = useLoad<unknown[]>(path);
  return (
    <Link to={to} className="tile">
      <span className="tile-count">{loading ? "..." : error ? "!" : (data?.length ?? 0)}</span>
      <span>{label}</span>
    </Link>
  );
}

/** What is waiting for this user, by role. */
export function WorklistPage() {
  const { session } = useAuth();
  const tiles: { to: string; label: string; path: string; roles: string[] }[] = [
    { to: "/customers", label: "Customers awaiting KYC verification", path: "/api/v1/customers?status=PENDING_VERIFICATION", roles: ["BRANCH_MANAGER", "OPERATIONS"] },
    { to: "/applications", label: "Loan applications awaiting decision", path: "/api/v1/lending/applications", roles: ["BRANCH_MANAGER", "LOAN_OFFICER"] },
    { to: "/disbursements", label: "Disbursements awaiting approval", path: "/api/v1/payments/mpesa/b2c/disbursements", roles: ["BRANCH_MANAGER", "SUPERVISOR"] },
    { to: "/disbursements", label: "Disbursements needing attention", path: "/api/v1/payments/mpesa/b2c/disbursements/attention", roles: ["OPERATIONS", "SUPERVISOR"] },
    { to: "/mpesa", label: "STK requests needing attention", path: "/api/v1/payments/mpesa/stk-requests/attention", roles: ["OPERATIONS", "SUPERVISOR"] },
    { to: "/mpesa", label: "M-Pesa close-outs awaiting approval", path: "/api/v1/payments/mpesa/resolutions", roles: ["OPERATIONS", "SUPERVISOR"] },
    { to: "/suspense", label: "Unmatched receipts in suspense", path: "/api/v1/payments/suspense/items", roles: ["OPERATIONS", "SUPERVISOR"] },
  ];
  const visible = tiles.filter((t) => hasAnyRole(session, ...t.roles));
  return (
    <>
      <h1>Worklist</h1>
      {visible.length === 0 ? (
        <p className="empty">Nothing is assigned to your roles here. Ask an administrator if you expected work.</p>
      ) : (
        <div className="tiles">
          {visible.map((t) => (
            <Tile key={t.label} to={t.to} label={t.label} path={t.path} />
          ))}
        </div>
      )}
    </>
  );
}
