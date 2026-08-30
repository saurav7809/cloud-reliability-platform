import { useState, type FormEvent } from "react";
import { ApiError, login } from "../api/client";

interface Props {
  onLoggedIn: (token: string) => void;
}

export function LoginForm({ onLoggedIn }: Props) {
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
      setError(err instanceof ApiError ? err.message : "Could not reach the backend");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form className="login-form" onSubmit={handleSubmit}>
      <h1>AegisCloud</h1>
      <p className="subtitle">Sign in to continue</p>

      <label htmlFor="email">Email</label>
      <input
        id="email"
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        required
      />

      <label htmlFor="password">Password</label>
      <input
        id="password"
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        required
      />

      {error && <p className="error">{error}</p>}

      <button type="submit" disabled={loading}>
        {loading ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
