import { NavLink, Outlet } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";

/** Navigation entries and the roles that see them (the API enforces the same roles). */
export const NAV: { to: string; label: string; roles: string[] }[] = [
  { to: "/", label: "Worklist", roles: [] },
  { to: "/customers", label: "Customers", roles: ["LOAN_OFFICER", "TELLER", "BRANCH_MANAGER", "OPERATIONS", "SUPERVISOR"] },
  { to: "/teller", label: "Teller", roles: ["TELLER", "BRANCH_MANAGER", "SUPERVISOR"] },
  { to: "/applications", label: "Loan applications", roles: ["LOAN_OFFICER", "BRANCH_MANAGER"] },
  { to: "/disbursements", label: "Disbursements", roles: ["LOAN_OFFICER", "OPERATIONS", "BRANCH_MANAGER", "SUPERVISOR"] },
  { to: "/mpesa", label: "M-Pesa close-out", roles: ["OPERATIONS", "SUPERVISOR"] },
  { to: "/suspense", label: "Suspense", roles: ["OPERATIONS", "SUPERVISOR"] },
  { to: "/loans", label: "Loan schedule", roles: ["LOAN_OFFICER", "BRANCH_MANAGER", "TELLER", "OPERATIONS"] },
  { to: "/portfolio", label: "Portfolio at risk", roles: ["BRANCH_MANAGER", "SUPERVISOR", "OPERATIONS", "LOAN_OFFICER"] },
  { to: "/write-offs", label: "Write-offs", roles: ["BRANCH_MANAGER", "OPERATIONS", "SUPERVISOR"] },
  { to: "/end-of-day", label: "End of day", roles: ["OPERATIONS", "BRANCH_MANAGER", "SUPERVISOR"] },
];

export function Layout() {
  const { session, signOut } = useAuth();
  const items = NAV.filter((n) => n.roles.length === 0 || hasAnyRole(session, ...n.roles));
  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand">Technopick</div>
        <nav aria-label="Main">
          {items.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === "/"}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="who">
          <div>{session?.name}</div>
          <div className="muted small">{session?.roles.join(", ") || "no roles"}</div>
          <button type="button" className="link" onClick={() => void signOut()}>
            Sign out
          </button>
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
