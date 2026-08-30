import type { ReactNode } from "react";
import type { Provider } from "../api/client";

export function Logo({ size = 30 }: { size?: number }) {
  return (
    <svg
      className="brand-mark"
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M16 2.5 4.5 7.2v9.1c0 7 4.9 11.9 11.5 13.2 6.6-1.3 11.5-6.2 11.5-13.2V7.2L16 2.5Z"
        fill="var(--accent-dim)"
        stroke="var(--accent)"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path
        d="M11 16.2l3.4 3.4 6.6-6.8"
        stroke="var(--accent)"
        strokeWidth="2.1"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function Brand({ size }: { size?: number }) {
  return (
    <div className="brand">
      <Logo size={size} />
      <span className="brand-name">AegisCloud</span>
    </div>
  );
}

export function Card({
  title,
  meta,
  children,
  className = "",
}: {
  title?: string;
  meta?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`card ${className}`}>
      {title && (
        <div className="card-title">
          {title}
          {meta && <span>{meta}</span>}
        </div>
      )}
      {children}
    </div>
  );
}

export function Stat({
  label,
  value,
  sub,
  tone,
}: {
  label: string;
  value: string | number;
  sub?: string;
  tone?: "good" | "warn" | "bad";
}) {
  const color =
    tone === "good"
      ? "var(--good)"
      : tone === "warn"
        ? "var(--warn)"
        : tone === "bad"
          ? "var(--bad)"
          : "var(--text-dim)";
  return (
    <div className="card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && (
        <div className="stat-sub" style={{ color }}>
          {sub}
        </div>
      )}
    </div>
  );
}

const PROVIDER_META: Record<Provider, { short: string; color: string; label: string }> = {
  AWS: { short: "AWS", color: "var(--aws)", label: "EKS" },
  GCP: { short: "GCP", color: "var(--gcp)", label: "GKE" },
  AZURE: { short: "AZ", color: "var(--azure)", label: "AKS" },
  KIND: { short: "K", color: "var(--kind)", label: "kind" },
  ON_PREM: { short: "OP", color: "var(--text-dim)", label: "on-prem" },
};

export function providerColor(p: Provider) {
  return PROVIDER_META[p]?.color ?? "var(--text-dim)";
}

export function ProviderTag({ provider }: { provider: Provider }) {
  const m = PROVIDER_META[provider] ?? PROVIDER_META.ON_PREM;
  return (
    <span className="provider-tag">
      <span className="provider-chip" style={{ background: m.color }}>
        {m.short}
      </span>
      <span style={{ color: "var(--text-strong)" }}>{provider}</span>
    </span>
  );
}

type Tone = "good" | "warn" | "bad" | "info" | "mute";

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`badge badge-${tone}`}>
      <span className="dot" />
      {children}
    </span>
  );
}

export function StatusBadge({ status }: { status: string }) {
  const map: Record<string, Tone> = {
    HEALTHY: "good",
    ACTIVE: "good",
    CONNECTED: "good",
    COMPLETED: "good",
    RESOLVED: "good",
    ENFORCING: "info",
    READY: "info",
    RUNNING: "info",
    DEPLOYING: "info",
    ACKNOWLEDGED: "warn",
    DEGRADED: "warn",
    MEDIUM: "warn",
    OPEN: "bad",
    FAILED: "bad",
    UNREACHABLE: "bad",
    CRITICAL: "bad",
    HIGH: "bad",
    REJECTED_BY_POLICY: "bad",
    LOW: "mute",
    ABORTED: "mute",
  };
  return <Badge tone={map[status] ?? "mute"}>{status.replace(/_/g, " ")}</Badge>;
}

export function scoreTone(score: number) {
  return score >= 95 ? "good" : score >= 85 ? "warn" : "bad";
}

export function Score({ value }: { value: number }) {
  return <span className={`score score-${scoreTone(value)}`}>{value.toFixed(1)}</span>;
}

export function Bar({ pct, tone }: { pct: number; tone: "good" | "warn" | "bad" }) {
  const color =
    tone === "good" ? "var(--good)" : tone === "warn" ? "var(--warn)" : "var(--bad)";
  return (
    <div className="bar">
      <div
        className="bar-fill"
        style={{ width: `${Math.max(0, Math.min(100, pct))}%`, background: color }}
      />
    </div>
  );
}

export function timeAgo(iso: string) {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export function money(v: number) {
  return `$${v.toLocaleString("en-US", { maximumFractionDigits: 0 })}`;
}

export function DemoNote({ children }: { children: ReactNode }) {
  return (
    <div className="demo-note">
      <span aria-hidden="true">ⓘ</span>
      <span>{children}</span>
    </div>
  );
}
