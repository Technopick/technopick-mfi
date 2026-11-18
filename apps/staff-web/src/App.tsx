import { useEffect, useMemo, useState } from "react";
import { createBrowserRouter, RouterProvider } from "react-router";
import { ApiProvider } from "./api/ApiContext";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { loadConfig, type AppConfig } from "./config";
import { routes } from "./routes";

function SignedIn({ config }: { config: AppConfig }) {
  const { session, loading, error, signIn } = useAuth();
  const router = useMemo(() => createBrowserRouter(routes), []);
  if (loading) return <p className="center muted">Signing in...</p>;
  if (!session) {
    return (
      <div className="center">
        <h1>Technopick Staff</h1>
        {error && <div className="problem" role="alert">Sign-in failed: {error}</div>}
        <button type="button" onClick={() => void signIn()}>Sign in</button>
      </div>
    );
  }
  return (
    <ApiProvider baseUrl={config.apiBaseUrl} token={session.accessToken}>
      <RouterProvider router={router} />
    </ApiProvider>
  );
}

export function App() {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    loadConfig().then(setConfig, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);
  if (error) return <div className="center problem" role="alert">{error}</div>;
  if (!config) return <p className="center muted">Loading...</p>;
  return (
    <AuthProvider config={config}>
      <SignedIn config={config} />
    </AuthProvider>
  );
}
