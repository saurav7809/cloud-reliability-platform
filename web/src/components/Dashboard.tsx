import { useEffect, useState } from "react";
import { ApiError, me, type MeResponse } from "../api/client";

interface Props {
  token: string;
  onLogout: () => void;
}

export function Dashboard({ token, onLogout }: Props) {
  const [profile, setProfile] = useState<MeResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    me(token)
      .then(setProfile)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Session expired"));
  }, [token]);

  return (
    <div className="dashboard">
      <header>
        <h1>AegisCloud</h1>
        <button onClick={onLogout}>Sign out</button>
      </header>

      {error && <p className="error">{error}</p>}

      {profile && (
        <div className="card">
          <p>
            Signed in as <strong>{profile.email}</strong>
          </p>
          <p>
            Role: <strong>{profile.role}</strong>
          </p>
        </div>
      )}

      <p className="placeholder">
        Clusters, services, and reliability scores land here starting Phase 3
        (Deployment Engine).
      </p>
    </div>
  );
}
