import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  applyRecommendation,
  dismissRecommendation,
  getRecommendations,
  refreshRecommendations,
  type Recommendation,
} from "../api/client";
import { Badge, Card } from "../components/ui";

const IMPACT_TONE: Record<string, "good" | "warn" | "bad" | "info"> = {
  NONE: "good",
  LOW: "info",
  MEDIUM: "warn",
  HIGH: "bad",
};

function safeToApply(r: Recommendation): boolean {
  return r.evidence?.safeToApply === true;
}

/**
 * Cost and performance advice.
 *
 * Withheld recommendations are shown alongside the safe ones rather than hidden. The
 * saving they describe is real, and an operator should be able to see that the
 * platform found it and is declining to offer it — with the reason — rather than
 * wonder why an obvious saving never appeared.
 */
export function OptimizationPage({ token }: { token: string }) {
  const [rows, setRows] = useState<Recommendation[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setRows(await getRecommendations(token, "OPEN"));
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load recommendations");
    }
  }, [token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function reexamine() {
    setBusy(true);
    setNote(null);
    try {
      const report = await refreshRecommendations(token);
      setNote(
        `${report.recommendations} recommendation(s), ${report.withheld} withheld, ` +
          `$${report.totalMonthlySavingUsd.toFixed(2)}/month safe to take`,
      );
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not refresh");
    } finally {
      setBusy(false);
    }
  }

  async function act(r: Recommendation, take: boolean) {
    setBusy(true);
    setError(null);
    try {
      const result = take
        ? await applyRecommendation(token, r.id)
        : await dismissRecommendation(token, r.id, "dismissed from the dashboard");
      setNote(`${r.serviceName}: ${result.detail}`);
      await refresh();
    } catch (err) {
      // A refusal is the platform working, not a failure of the page, so it is shown
      // as the message it is rather than as a generic error.
      setError(err instanceof ApiError ? err.message : "Could not act on the recommendation");
    } finally {
      setBusy(false);
    }
  }

  const safeSaving = rows
    .filter(safeToApply)
    .reduce((sum, r) => sum + Math.max(0, r.estimatedMonthlySavingUsd), 0);
  const withheldSaving = rows
    .filter((r) => !safeToApply(r))
    .reduce((sum, r) => sum + Math.max(0, r.estimatedMonthlySavingUsd), 0);

  return (
    <>
      <div className="page-head">
        <h1>Optimization</h1>
        <p>
          Cost and performance advice, with one rule above the rest: reliability is not
          currency. Where a saving would spend an error budget the service cannot
          spare, the platform states the saving and declines to offer it.
        </p>
      </div>

      {error && <p className="error-msg">{error}</p>}

      <Card title="Open advice" meta={`${rows.length}`}>
        <p className="stat-line">
          safe to take <strong>${safeSaving.toFixed(2)}</strong>/month · withheld{" "}
          <strong>${withheldSaving.toFixed(2)}</strong>/month
        </p>
        <button className="btn btn-primary" onClick={reexamine} disabled={busy}>
          {busy ? "Examining…" : "Re-examine every service"}
        </button>
        {note && <p className="hint">{note}</p>}
      </Card>

      {rows.map((r) => (
        <Card
          key={r.id}
          title={r.title}
          meta={`${r.serviceName} @ ${r.clusterName}`}
        >
          <p className="muted">{r.rationale}</p>
          <p className="stat-line">
            <Badge tone={IMPACT_TONE[r.reliabilityImpact] ?? "info"}>
              {r.reliabilityImpact.toLowerCase()} reliability impact
            </Badge>{" "}
            {r.estimatedMonthlySavingUsd >= 0 ? (
              <>saves ${r.estimatedMonthlySavingUsd.toFixed(2)}/month</>
            ) : (
              <>costs ${Math.abs(r.estimatedMonthlySavingUsd).toFixed(2)}/month</>
            )}{" "}
            {safeToApply(r) ? (
              <Badge tone="good">safe to apply</Badge>
            ) : (
              <Badge tone="bad">not offered as safe</Badge>
            )}
          </p>
          <div>
            <button
              className="btn btn-ghost"
              disabled={busy || !safeToApply(r)}
              onClick={() => act(r, true)}
              title={
                safeToApply(r)
                  ? "Apply, subject to the Policy Engine"
                  : "Withheld: applying this would spend reliability the service cannot spare"
              }
            >
              Apply
            </button>
            <button className="btn btn-ghost" disabled={busy} onClick={() => act(r, false)}>
              Dismiss
            </button>
          </div>
        </Card>
      ))}

      {rows.length === 0 && (
        <Card title="Nothing to advise">
          <p className="muted">
            No open recommendations. Advice needs measurements: a service nothing has
            probed gets an observability finding rather than a fabricated saving.
          </p>
        </Card>
      )}
    </>
  );
}
