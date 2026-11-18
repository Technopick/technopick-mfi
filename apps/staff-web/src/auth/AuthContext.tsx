import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import type { AppConfig } from "../config";
import { sessionFromToken, type Session } from "./session";

interface AuthState {
  session: Session | null;
  loading: boolean;
  error: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * OIDC authorization code flow with PKCE against the configured identity provider. Tokens are kept
 * in session storage (cleared when the tab closes) and renewed silently before expiry. There is
 * no development bypass: without an identity provider the app shows the sign-in error.
 */
export function AuthProvider({ config, children }: { config: AppConfig; children: ReactNode }) {
  const manager = useMemo(
    () =>
      new UserManager({
        authority: config.oidcAuthority,
        client_id: config.oidcClientId,
        redirect_uri: `${window.location.origin}/auth/callback`,
        post_logout_redirect_uri: window.location.origin,
        response_type: "code",
        scope: config.oidcScope,
        automaticSilentRenew: true,
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      }),
    [config],
  );
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        if (window.location.pathname === "/auth/callback") {
          const signedIn = await manager.signinRedirectCallback();
          window.history.replaceState({}, "", (signedIn.state as string | undefined) ?? "/");
          if (active) setUser(signedIn);
        } else {
          const existing = await manager.getUser();
          if (active) setUser(existing && !existing.expired ? existing : null);
        }
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (active) setLoading(false);
      }
    })();
    const onLoaded = (u: User) => setUser(u);
    const onUnloaded = () => setUser(null);
    manager.events.addUserLoaded(onLoaded);
    manager.events.addUserUnloaded(onUnloaded);
    return () => {
      active = false;
      manager.events.removeUserLoaded(onLoaded);
      manager.events.removeUserUnloaded(onUnloaded);
    };
  }, [manager]);

  const value = useMemo<AuthState>(() => {
    let session: Session | null = null;
    if (user?.access_token) {
      try {
        session = sessionFromToken(user.access_token, config.rolesClaim, user.profile.name ?? undefined);
      } catch {
        session = null;
      }
    }
    return {
      session,
      loading,
      error,
      signIn: () => manager.signinRedirect({ state: window.location.pathname + window.location.search }),
      signOut: () => manager.signoutRedirect(),
    };
  }, [user, loading, error, manager, config.rolesClaim]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}

/** For tests and stories: provide a fixed state without an identity provider. */
export const TestAuthContext = AuthContext;
