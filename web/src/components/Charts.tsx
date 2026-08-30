import type { Provider } from "../api/client";
import { providerColor } from "./ui";

/** Reliability score over time — area + line, drawn as plain SVG. */
export function TrendChart({ points }: { points: { date: string; score: number }[] }) {
  if (points.length < 2) return null;

  const W = 660;
  const H = 170;
  const padL = 34;
  const padB = 22;
  const padT = 10;

  const values = points.map((p) => p.score);
  const min = Math.floor(Math.min(...values) - 2);
  const max = Math.ceil(Math.max(...values) + 2);
  const span = max - min || 1;

  const x = (i: number) => padL + (i / (points.length - 1)) * (W - padL - 10);
  const y = (v: number) => padT + (1 - (v - min) / span) * (H - padT - padB);

  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i)},${y(p.score)}`).join(" ");
  const area = `${line} L${x(points.length - 1)},${H - padB} L${x(0)},${H - padB} Z`;

  const ticks = [min, min + span / 2, max];

  return (
    <svg className="chart-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Reliability score trend">
      <defs>
        <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.28" />
          <stop offset="100%" stopColor="var(--accent)" stopOpacity="0" />
        </linearGradient>
      </defs>

      {ticks.map((t) => (
        <g key={t}>
          <line
            x1={padL}
            x2={W - 10}
            y1={y(t)}
            y2={y(t)}
            stroke="var(--border)"
            strokeWidth="1"
          />
          <text x={padL - 8} y={y(t) + 3.5} textAnchor="end" fontSize="10" fill="var(--text-dim)">
            {t.toFixed(0)}
          </text>
        </g>
      ))}

      <path d={area} fill="url(#trendFill)" />
      <path d={line} fill="none" stroke="var(--accent)" strokeWidth="2" strokeLinejoin="round" />

      {points.map((p, i) =>
        i % 3 === 0 || i === points.length - 1 ? (
          <text
            key={p.date}
            x={x(i)}
            y={H - 6}
            textAnchor="middle"
            fontSize="10"
            fill="var(--text-dim)"
          >
            {p.date}
          </text>
        ) : null,
      )}

      <circle
        cx={x(points.length - 1)}
        cy={y(points[points.length - 1].score)}
        r="3.5"
        fill="var(--accent)"
        stroke="var(--surface)"
        strokeWidth="2"
      />
    </svg>
  );
}

/** Horizontal bars comparing reliability score per cloud provider. */
export function ProviderBars({
  data,
}: {
  data: { provider: Provider; score: number; targets: number }[];
}) {
  if (!data.length) return null;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 13 }}>
      {data.map((d) => (
        <div key={d.provider}>
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              fontSize: 12.5,
              marginBottom: 5,
            }}
          >
            <span style={{ color: "var(--text-strong)", fontWeight: 550 }}>
              {d.provider}
              <span style={{ color: "var(--text-dim)", fontWeight: 400 }}>
                {" "}
                · {d.targets} target{d.targets === 1 ? "" : "s"}
              </span>
            </span>
            <span
              className="mono"
              style={{ color: "var(--text-strong)", fontWeight: 600 }}
            >
              {d.score.toFixed(1)}
            </span>
          </div>
          <div className="bar" style={{ height: 7 }}>
            <div
              className="bar-fill"
              style={{
                width: `${d.score}%`,
                background: providerColor(d.provider),
              }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}
