/** Runtime configuration, served as /config.json so one build runs in every environment. */
export interface AppConfig {
  oidcAuthority: string;
  oidcClientId: string;
  oidcScope: string;
  rolesClaim: string;
  apiBaseUrl: string;
}

export async function loadConfig(fetchFn: typeof fetch = fetch): Promise<AppConfig> {
  const response = await fetchFn("/config.json", { cache: "no-store" });
  if (!response.ok) {
    throw new Error("config.json not found: the app is not configured for this environment");
  }
  const raw = (await response.json()) as Partial<AppConfig>;
  if (!raw.oidcAuthority || !raw.oidcClientId) {
    throw new Error("config.json must set oidcAuthority and oidcClientId");
  }
  if (!raw.oidcAuthority.startsWith("https://") && !raw.oidcAuthority.startsWith("http://localhost")) {
    throw new Error("oidcAuthority must use https");
  }
  return {
    oidcAuthority: raw.oidcAuthority,
    oidcClientId: raw.oidcClientId,
    oidcScope: raw.oidcScope ?? "openid profile",
    rolesClaim: raw.rolesClaim ?? "roles",
    apiBaseUrl: raw.apiBaseUrl ?? "",
  };
}
