import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  diagnoseNow,
  getIncident,
  getIncidents,
  getRcaAccuracy,
  judgeVerdict,
  type Incident,
  type Verdict,
} from "../api/client";
import { Badge, Card, timeAgo } from "../components/ui";

const ASSESSMENT_TONE: Record<string, "good" | "warn" | "bad" | "info"> = {
  LIKELY_CAUSE: "bad",
  POSSIBLE_CAUSE: "warn",
  LIKELY_SYMPTOM: "info",
};

const SIGNAL_LABEL: Record<string, string> = {
  GRAPH_POSITION: "graph position",
  TEMPORAL_ORDER: "what failed first",
  CHANGE_EVENT: "recent changes",
  RESOURCE_SATURATION: "the pods themselves",
};

/**
 * Incidents and their diagnoses.
 *
 * The evidence is shown in full rather than summarised into a score. A verdict that
 * cannot be checked is one an operator has to take on faith at exactly the moment
 * they should not, so every fact the engine used is on the screen beside the number
 * it produced — including the facts that argue against a candidate.
 */
export function DiagnosticsPage({ token }: { token: string }) {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [verdicts, setVerdicts] = useState<Verdict[]>([]);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [accuracy, setAccuracy] = useState<{
    correct: number;
    total: number;
    precisionAt1: number;
    detail: string[];
  } | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const rows = await getIncidents(token);
      setIncidents(rows);
      setAccuracy(await getRcaAccuracy(token));
      setError(null);
      if (rows.length > 0 && !selected) {
        setSelected(rows[0]);
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load incidents");
    }
  }, [token, selected]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (!selected) return;
    getIncident(token, selected.id)
      .then((d) => setVerdicts(d.verdicts))
      .catch(() => setVerdicts([]));
  }, [token, selected]);

  async function handleDiagnose() {
    setBusy(true);
    setNote(null);
    try {
      const result = await diagnoseNow(token);
      // "nothing is degraded" is a real answer, not an empty one. Saying so beats
      // opening an incident nobody asked for.
      setNote(result.summary ? `${result.title} — ${result.summary}` : (result.status ?? ""));
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Diagnosis failed");
    } finally {
      setBusy(false);
    }
  }

  async function judge(rank: number, verdict: "CORRECT" | "INCORRECT") {
    if (!selected) return;
    try {
      await judgeVerdict(token, selected.id, rank, verdict);
      const d = await getIncident(token, selected.id);
      setVerdicts(d.verdicts);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not record the judgement");
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Diagnostics</h1>
        <p>
          When several services fail at once, which one is actually broken. Every
          verdict carries the facts it rests on — a candidate the engine cannot
          explain is never shown at all.
        </p>
      </div>

      {error && <p className="error-msg">{error}</p>}

      <Card
        title="Accuracy against known causes"
        meta={accuracy ? `${accuracy.correct}/${accuracy.total} scored` : "—"}
      >
        <p className="muted">
          Chaos experiments are the only incidents whose true cause the platform knows,
          because it caused them. Each run's window is re-analysed through the same code
          path a live diagnosis uses.
        </p>
        {accuracy && (
          <p className="stat-line">
            precision@1 <strong>{(accuracy.precisionAt1 * 100).toFixed(0)}%</strong> over{" "}
            {accuracy.total} scored run{accuracy.total === 1 ? "" : "s"}
          </p>
        )}
        <button className="btn btn-primary" onClick={handleDiagnose} disabled={busy}>
          {busy ? "Diagnosing…" : "Diagnose what is degraded now"}
        </button>
        {note && <p className="hint">{note}</p>}
      </Card>

      <Card title="Incidents" meta={`${incidents.length} recorded`}>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Incident</th>
                <th>Root cause</th>
                <th>Confidence</th>
                <th>Affected</th>
                <th>Status</th>
                <th>Started</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((i) => (
                <tr
                  key={i.id}
                  className={selected?.id === i.id ? "row-selected" : "row-clickable"}
                  onClick={() => setSelected(i)}
                >
                  <td className="td-strong">{i.title}</td>
                  <td>{i.rootCauseService ?? "—"}</td>
                  <td className="mono">
                    {i.confidence == null ? "—" : i.confidence.toFixed(2)}
                  </td>
                  <td className="mono">{i.blastRadiusCount}</td>
                  <td>
                    <Badge tone={i.status === "RESOLVED" ? "good" : "warn"}>{i.status}</Badge>
                  </td>
                  <td className="small">{timeAgo(i.startedAt)}</td>
                </tr>
              ))}
              {incidents.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">
                    No incidents recorded. An incident is opened when a measured service
                    falls below its degradation threshold — not on a schedule.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>

      {selected && (
        <Card title={`Verdicts — ${selected.title}`} meta={`${verdicts.length} candidates`}>
          {verdicts.length === 0 && (
            <p className="muted">
              No candidate could be supported by evidence, so the incident is recorded
              without a verdict.
            </p>
          )}
          {verdicts.map((v) => {
            const assessment = String(v.evidence?.assessment ?? "");
            const facts = v.evidence?.facts ?? [];
            return (
              <div className="verdict" key={v.rank}>
                <div className="verdict-head">
                  <span className="verdict-rank">#{v.rank}</span>
                  <span className="td-strong">{v.serviceName}</span>
                  <Badge tone={ASSESSMENT_TONE[assessment] ?? "info"}>
                    {assessment.replace(/_/g, " ").toLowerCase() || "candidate"}
                  </Badge>
                  <span className="mono">{v.confidence.toFixed(2)}</span>
                  <span className="verdict-actions">
                    <button className="btn btn-ghost" onClick={() => judge(v.rank, "CORRECT")}>
                      Correct
                    </button>
                    <button className="btn btn-ghost" onClick={() => judge(v.rank, "INCORRECT")}>
                      Wrong
                    </button>
                    {v.humanVerdict && <Badge tone="info">{v.humanVerdict}</Badge>}
                  </span>
                </div>
                <ul className="evidence">
                  {facts.map((f, i) => (
                    <li key={i} className={f.weight < 0 ? "evidence-against" : ""}>
                      <span className="evidence-signal">
                        {SIGNAL_LABEL[f.signal] ?? f.signal}
                      </span>
                      {f.detail}
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </Card>
      )}
    </>
  );
}
