import { useState } from "react";
import type { Alert } from "../api/client";
import { acknowledgeAlert, resolveAlert } from "../api/client";
import { Card, StatusBadge, timeAgo } from "../components/ui";

export function AlertsPage({
  alerts,
  token,
  onChanged,
}: {
  alerts: Alert[];
  token: string;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function act(id: string, kind: "ack" | "resolve") {
    setBusy(id);
    setError(null);
    try {
      await (kind === "ack" ? acknowledgeAlert(token, id) : resolveAlert(token, id));
      onChanged();
    } catch {
      setError("Could not update that alert. Please retry.");
    } finally {
      setBusy(null);
    }
  }

  const open = alerts.filter((a) => a.status === "OPEN").length;

  return (
    <>
      <div className="page-head">
        <h1>Alerts</h1>
        <p>
          Raised when SLO burn-rate crosses a threshold. {open} open of {alerts.length}{" "}
          total.
        </p>
      </div>

      {error && <p className="error-msg">{error}</p>}

      <Card title="Alert Feed" meta="newest first">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Severity</th>
                <th>Target</th>
                <th style={{ width: "40%" }}>Message</th>
                <th>Opened</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((a) => (
                <tr key={a.id}>
                  <td>
                    <StatusBadge status={a.severity} />
                  </td>
                  <td className="td-strong">{a.targetLabel}</td>
                  <td style={{ whiteSpace: "normal" }}>{a.message}</td>
                  <td style={{ color: "var(--text-dim)" }}>{timeAgo(a.openedAt)}</td>
                  <td>
                    <StatusBadge status={a.status} />
                  </td>
                  <td>
                    <div className="actions">
                      {a.status === "OPEN" && (
                        <button
                          className="btn btn-ghost"
                          disabled={busy === a.id}
                          onClick={() => act(a.id, "ack")}
                        >
                          Acknowledge
                        </button>
                      )}
                      {a.status !== "RESOLVED" && (
                        <button
                          className="btn btn-ghost"
                          disabled={busy === a.id}
                          onClick={() => act(a.id, "resolve")}
                        >
                          Resolve
                        </button>
                      )}
                      {a.status === "RESOLVED" && (
                        <span style={{ color: "var(--text-dim)", fontSize: 12 }}>—</span>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
