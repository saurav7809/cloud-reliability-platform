import { useEffect, useState, type FormEvent } from "react";
import { ApiError, login, signUp, signupEnabled } from "../api/client";
import { Brand } from "./ui";

type Mode = "signin" | "signup";

export function LoginForm({ onLoggedIn }: { onLoggedIn: (token: string) => void }) {
  const [mode, setMode] = useState<Mode>("signin");
  const [email, setEmail] = useState("admin@aegiscloud.local");
  const [password, setPassword] = useState("");
  const [organisation, setOrganisation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [canSignUp, setCanSignUp] = useState(false);

  // Whether registration is offered at all is the deployment's decision, not the
  // frontend's. A platform installed inside one company usually wants accounts
  // created by an administrator, and showing a sign-up link that always fails
  // would be worse than showing none.
  useEffect(() => {
    signupEnabled().then(setCanSignUp).catch(() => setCanSignUp(false));
  }, []);

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setPassword("");
    // The seeded admin address is a convenience for signing in and precisely the
    // wrong default when registering.
    setEmail(next === "signup" ? "" : "admin@aegiscloud.local");
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { accessToken } =
        mode === "signin"
          ? await login(email, password)
          : await signUp(email, password, organisation);
      onLoggedIn(accessToken);
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Could not reach the control plane",
      );
    } finally {
      setLoading(false);
    }
  }

  const signingUp = mode === "signup";

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={handleSubmit}>
        <Brand />
        <h1>{signingUp ? "Create an organisation" : "Sign in"}</h1>
        <p className="login-sub">
          {signingUp
            ? "You will be its first administrator"
            : "Cloud-agnostic reliability & evaluation platform"}
        </p>

        {signingUp && (
          <label className="field">
            <span>Organisation</span>
            <input
              type="text"
              value={organisation}
              onChange={(e) => setOrganisation(e.target.value)}
              placeholder="Acme Payments"
              autoComplete="organization"
              required
            />
          </label>
        )}

        <label className="field">
          <span>Email</span>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
          />
        </label>

        <label className="field">
          <span>Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={signingUp ? "new-password" : "current-password"}
            required
          />
          {signingUp && (
            <small className="field-hint">
              At least 12 characters. Length matters more than symbols, so a
              memorable phrase is fine.
            </small>
          )}
        </label>

        {error && <p className="error-msg">{error}</p>}

        <button className="btn btn-primary" type="submit" disabled={loading}>
          {loading
            ? signingUp
              ? "Creating…"
              : "Signing in…"
            : signingUp
              ? "Create organisation"
              : "Sign in"}
        </button>

        {canSignUp && (
          <p className="hint">
            {signingUp ? (
              <>
                Already have an account?{" "}
                <button type="button" className="link-btn" onClick={() => switchMode("signin")}>
                  Sign in
                </button>
              </>
            ) : (
              <>
                New here?{" "}
                <button type="button" className="link-btn" onClick={() => switchMode("signup")}>
                  Create an organisation
                </button>
                {" — it starts empty; you see nothing of anyone else's fleet."}
              </>
            )}
          </p>
        )}

        {!signingUp && (
          <p className="hint">
            Local dev credentials seed from <code>AEGISCLOUD_ADMIN_EMAIL</code> and{" "}
            <code>AEGISCLOUD_ADMIN_PASSWORD</code>. Defaults are{" "}
            <code>admin@aegiscloud.local</code> / <code>changeme123</code>.
          </p>
        )}
      </form>
    </div>
  );
}
