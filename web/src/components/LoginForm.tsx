import { useState, type FormEvent } from "react";
import { ApiError, login } from "../api/client";
import { Brand } from "./ui";

export function LoginForm({ onLoggedIn }: { onLoggedIn: (token: string) => void }) {
  const [email, setEmail] = useState("admin@aegiscloud.local");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { accessToken } = await login(email, password);
      onLoggedIn(accessToken);
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Could not reach the backend on :8080",
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={handleSubmit}>
        <Brand />
        <h1>Sign in</h1>
        <p className="login-sub">Cloud-agnostic reliability &amp; evaluation platform</p>

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
            autoComplete="current-password"
            required
          />
        </label>

        {error && <p className="error-msg">{error}</p>}

        <button className="btn btn-primary" type="submit" disabled={loading}>
          {loading ? "Signing in…" : "Sign in"}
        </button>

        <p className="hint">
          Local dev credentials seed from <code>AEGISCLOUD_ADMIN_EMAIL</code> and{" "}
          <code>AEGISCLOUD_ADMIN_PASSWORD</code>. Defaults are{" "}
          <code>admin@aegiscloud.local</code> / <code>changeme123</code>.
        </p>
      </form>
    </div>
  );
}
