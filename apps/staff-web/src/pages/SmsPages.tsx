import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useApi } from "../api/ApiContext";
import type { Schemas } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { hasAnyRole } from "../auth/session";
import { Empty, Loading, ProblemBox, Section, Status, when } from "../components/ui";
import { useAction, useLoad } from "../hooks";

type MemberSms = Schemas["MemberSms"];
type SmsView = Schemas["SmsView"];
type SmsSettings = Schemas["SmsSettings"];
type SmsOverview = Schemas["SmsOverview"];

const TEMPLATE_TEXT: Record<string, string> = {
  PAYMENT_LOAN: "Loan payment received",
  PAYMENT_SAVINGS: "Savings deposit received",
  LOAN_DUE_SOON: "Instalment due soon",
  LOAN_MISSED: "Instalment missed",
  PIN_SETUP_READY: "PIN setup ready",
  PIN_SET: "PIN set",
  PIN_CHANGED: "PIN changed",
  PIN_LOCKED: "PIN locked",
};

const STATUS_TEXT: Record<string, string> = {
  QUEUED: "Waiting to send",
  DISPATCHED: "Sending",
  SENT: "Sent",
  DELIVERED: "Delivered",
  FAILED: "Failed",
  SUPPRESSED: "Not sent",
};

function statusOf(m: SmsView): string {
  if (m.status === "SUPPRESSED") return m.suppressedReason === "MEMBER_OPTED_OUT" ? "Not sent: member opted out" : "Not sent: member not active";
  if (m.status === "QUEUED" && m.attempts && m.attempts > 0) return `Retrying (${m.attempts} tried)`;
  return STATUS_TEXT[m.status ?? ""] ?? m.status ?? "";
}

function MessageTable({ messages, caption, showMember = false }: { messages: SmsView[]; caption: string; showMember?: boolean }) {
  return (
    <table>
      <caption>{caption}</caption>
      <thead><tr><th>Created</th>{showMember && <th>Member</th>}<th>Message</th><th>Status</th></tr></thead>
      <tbody>{messages.map((m) => (
        <tr key={m.messageId}>
          <td>{when(m.createdAt)}</td>
          {showMember && <td><Link to={`/customers/${m.customerNumber}`}>{m.customerNumber}</Link></td>}
          <td><div>{TEMPLATE_TEXT[m.template ?? ""] ?? m.template}{m.language === "sw" ? " (Swahili)" : ""}</div>
            <div className="muted small">{m.body}</div></td>
          <td>{statusOf(m)}
            {m.deliveredAt && <div className="muted small">{when(m.deliveredAt)}</div>}
            {m.status === "QUEUED" && m.notBefore && <div className="muted small">from {when(m.notBefore)}</div>}
            {m.lastError && m.status !== "DELIVERED" && <div className="muted small">{m.lastError}</div>}</td>
        </tr>
      ))}</tbody>
    </table>
  );
}

/** SMS to one member: their choices and the messages sent (ADR-0035). */
export function MemberSmsPanel({ customerNumber }: { customerNumber: string }) {
  const api = useApi();
  const { session } = useAuth();
  const sms = useLoad<MemberSms>(`/api/v1/customers/${encodeURIComponent(customerNumber)}/sms`);
  const action = useAction();
  const [language, setLanguage] = useState("en");
  const [paymentAlerts, setPaymentAlerts] = useState(true);
  const [reminders, setReminders] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const canEdit = hasAnyRole(session, "LOAN_OFFICER", "TELLER", "BRANCH_MANAGER", "OPERATIONS");
  const d = sms.data;

  useEffect(() => {
    if (!d?.preferences) return;
    setLanguage(d.preferences.language ?? "en");
    setPaymentAlerts(d.preferences.paymentAlerts ?? true);
    setReminders(d.preferences.reminders ?? true);
  }, [d]);

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await action.run(() => api.put<MemberSms>(`/api/v1/customers/${encodeURIComponent(customerNumber)}/sms-preferences`,
      { language, paymentAlerts, reminders }));
    if (r) {
      setNotice("SMS choices saved.");
      sms.reload();
    }
  }

  if (sms.loading && !d) return <Loading />;
  return (
    <Section title="SMS messages">
      <ProblemBox problem={sms.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      {d && !d.smsEnabled && <p className="muted small">SMS to members is switched off for this institution.</p>}
      {d && <p className="muted small">Sent to the member's registered phone {d.phone}. Security alerts (PIN) are always sent.</p>}
      {canEdit ? (
        <form className="form" onSubmit={(e) => void save(e)}>
          <label>Language
            <select value={language} onChange={(e) => setLanguage(e.target.value)}>
              <option value="en">English</option>
              <option value="sw">Swahili</option>
            </select>
          </label>
          <label className="check"><input type="checkbox" checked={paymentAlerts} onChange={(e) => setPaymentAlerts(e.target.checked)} />
            Payment alerts</label>
          <label className="check"><input type="checkbox" checked={reminders} onChange={(e) => setReminders(e.target.checked)} />
            Loan reminders</label>
          <div className="actions"><button type="submit" disabled={action.busy}>Save SMS choices</button></div>
        </form>
      ) : d?.preferences && (
        <p>{d.preferences.language === "sw" ? "Swahili" : "English"}; payment alerts {d.preferences.paymentAlerts ? "on" : "off"};
          {" "}loan reminders {d.preferences.reminders ? "on" : "off"}</p>
      )}
      {d?.preferences?.updatedBy && <p className="muted small">Choices last changed by {d.preferences.updatedBy}, {when(d.preferences.updatedAt)}</p>}
      {d && (d.messages?.length ? <MessageTable messages={d.messages} caption="Messages (newest first)" />
        : <Empty>No SMS yet.</Empty>)}
    </Section>
  );
}

function SettingsForm() {
  const api = useApi();
  const { session } = useAuth();
  const settings = useLoad<SmsSettings>("/api/v1/notifications/settings");
  const action = useAction();
  const [form, setForm] = useState({ enabled: false, signature: "", senderId: "", ussdCode: "", reminderHour: 8 });
  const [notice, setNotice] = useState<string | null>(null);
  const canEdit = hasAnyRole(session, "SUPERVISOR");

  useEffect(() => {
    const s = settings.data;
    if (s) setForm({ enabled: s.enabled ?? false, signature: s.signature ?? "", senderId: s.senderId ?? "",
      ussdCode: s.ussdCode ?? "", reminderHour: s.reminderHour ?? 8 });
  }, [settings.data]);

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await action.run(() => api.put<SmsSettings>("/api/v1/notifications/settings", form));
    if (r) {
      setNotice(r.enabled ? "SMS to members is on." : "SMS to members is off.");
      settings.reload();
    }
  }

  if (settings.loading && !settings.data) return <Loading />;
  return (
    <Section title="Settings" actions={<Status value={settings.data?.enabled ? "ON" : "OFF"} />}>
      <ProblemBox problem={settings.error ?? action.error} />
      {notice && <p className="ok" role="status">{notice}</p>}
      <form className="form" onSubmit={(e) => void save(e)}>
        <fieldset disabled={!canEdit}>
          <label className="check"><input type="checkbox" checked={form.enabled}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })} /> Send SMS to members</label>
          <label>Signature (starts every SMS)
            <input value={form.signature} maxLength={20} required onChange={(e) => setForm({ ...form, signature: e.target.value })} />
          </label>
          <label>Sender ID (as registered with the SMS provider; blank for its default)
            <input value={form.senderId} maxLength={11} onChange={(e) => setForm({ ...form, senderId: e.target.value })} />
          </label>
          <label>USSD code (for PIN setup messages)
            <input value={form.ussdCode} placeholder="*483*1#" onChange={(e) => setForm({ ...form, ussdCode: e.target.value })} />
          </label>
          <label>Reminder hour (07 to 19, tenant time)
            <input type="number" min={7} max={19} value={form.reminderHour}
              onChange={(e) => setForm({ ...form, reminderHour: Number(e.target.value) })} />
          </label>
          {canEdit && <div className="actions"><button type="submit" disabled={action.busy}>Save SMS settings</button></div>}
        </fieldset>
      </form>
      {!canEdit && <p className="muted small">Only a supervisor changes these settings.</p>}
      {settings.data?.updatedBy && <p className="muted small">Last changed by {settings.data.updatedBy}, {when(settings.data.updatedAt)}</p>}
    </Section>
  );
}

/** Operations and supervisors: SMS settings and messages by status. */
export function SmsPage() {
  const [status, setStatus] = useState("FAILED");
  const overview = useLoad<SmsOverview>(`/api/v1/notifications/sms?status=${status}`);
  const counts = overview.data?.lastSevenDays ?? {};
  return (
    <>
      <h1>SMS to members</h1>
      <SettingsForm />
      <Section title="Last seven days">
        <ProblemBox problem={overview.error} />
        <dl className="facts">{Object.entries(counts).map(([k, v]) => (
          <div key={k}><dt>{STATUS_TEXT[k] ?? k}</dt><dd>{v}</dd></div>
        ))}</dl>
        <label>Show
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {Object.keys(STATUS_TEXT).map((s) => <option key={s} value={s}>{STATUS_TEXT[s]}</option>)}
          </select>
        </label>
        {overview.loading && !overview.data ? <Loading /> : overview.data?.messages?.length
          ? <MessageTable messages={overview.data.messages} caption={`${STATUS_TEXT[status]} (newest first, up to 100)`} showMember />
          : <Empty>None.</Empty>}
      </Section>
    </>
  );
}
