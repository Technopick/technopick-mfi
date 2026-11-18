/** What the UI knows about the signed-in user, read from the access token (the API enforces). */
export interface Session {
  subject: string;
  name: string;
  tenantId: string | null;
  roles: string[];
  accessToken: string;
}

function decodePayload(token: string): Record<string, unknown> {
  const part = token.split(".")[1];
  if (!part) throw new Error("not a JWT");
  const base64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
  const json = decodeURIComponent(
    Array.from(atob(base64), (c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0")).join(""),
  );
  return JSON.parse(json) as Record<string, unknown>;
}

function claim(payload: Record<string, unknown>, path: string): unknown {
  // Supports nested claims such as "realm_access.roles" (Keycloak).
  return path.split(".").reduce<unknown>(
    (v, key) => (v && typeof v === "object" ? (v as Record<string, unknown>)[key] : undefined),
    payload,
  );
}

export function sessionFromToken(accessToken: string, rolesClaim: string, displayName?: string): Session {
  const payload = decodePayload(accessToken);
  const roles = claim(payload, rolesClaim);
  const subject = String(payload.sub ?? "");
  return {
    subject,
    name: displayName ?? String(payload.preferred_username ?? payload.name ?? subject),
    tenantId: typeof payload.tenant_id === "string" ? payload.tenant_id : null,
    roles: Array.isArray(roles) ? roles.map(String) : [],
    accessToken,
  };
}

export function hasAnyRole(session: Session | null, ...roles: string[]): boolean {
  return !!session && roles.some((r) => session.roles.includes(r));
}

/** The API's actor string for this user ("user:<sub>"), used to hide approve buttons on own items. */
export function actorOf(session: Session): string {
  return `user:${session.subject}`;
}
