import type { ReactNode } from "react";
import type { Problem } from "../api/client";
import { formatMoney } from "../money";

export function ProblemBox({ problem }: { problem: Problem | null }) {
  if (!problem) return null;
  return (
    <div className="problem" role="alert">
      <strong>{problem.title}</strong>
      {problem.detail && problem.detail !== problem.title && <div>{problem.detail}</div>}
      {problem.code && <div className="muted">Code: {problem.code}</div>}
    </div>
  );
}

export function Money({ amount, currency }: { amount: string | null | undefined; currency?: string }) {
  return <span className="num">{formatMoney(amount, currency)}</span>;
}

export function Status({ value }: { value: string | null | undefined }) {
  if (!value) return null;
  return <span className={`status status-${value.toLowerCase().replace(/[^a-z]+/g, "-")}`}>{value.replace(/_/g, " ")}</span>;
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head">
        <h2>{title}</h2>
        {actions}
      </header>
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Loading() {
  return <p className="muted" aria-busy="true">Loading...</p>;
}

export function when(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString("en-KE", { dateStyle: "medium", timeStyle: "short" });
}

/** Explains why an approve button is missing on the user's own item (four-eyes rule). */
export function OwnItemNote() {
  return <span className="muted">Your own request - a colleague must decide.</span>;
}
