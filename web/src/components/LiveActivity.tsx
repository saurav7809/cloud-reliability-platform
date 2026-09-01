import { useEffect, useRef, useState } from "react";
import { openControlPlaneStream, type LiveEvent } from "../api/client";
import { Card, Badge } from "./ui";

/** How many lines are kept. The feed is a window on now, not a log. */
const MAX_LINES = 60;

const TONE: Record<LiveEvent["kind"], "good" | "warn" | "info" | "bad"> = {
  connected: "info",
  "cycle-started": "info",
  "cycle-finished": "info",
  decision: "info",
  scaling: "good",
  healing: "warn",
  outcome: "good",
};

/**
 * The control loop as it happens.
 *
 * <p>Fed by Server-Sent Events rather than polling, because the point being shown is
 * that the platform reacts on its own: a decision that appears twenty seconds after
 * it was taken, on the next poll, does not demonstrate that.
 */
export function LiveActivity({ token }: { token: string }) {
  const [events, setEvents] = useState<LiveEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const source = openControlPlaneStream(token, (event) => {
      setConnected(true);
      setEvents((current) => [event, ...current].slice(0, MAX_LINES));
    });

    // onerror fires while the browser is reconnecting too, so this reports the
    // connection as down rather than tearing the stream down itself.
    source.onerror = () => setConnected(false);

    return () => source.close();
  }, [token]);

  return (
    <Card
      title="Live Activity"
      meta={connected ? "streaming" : "reconnecting"}
    >
      <div className="live-feed" ref={listRef}>
        {events.length === 0 ? (
          <p className="muted">
            Waiting for the next reconciliation cycle. Nothing is being polled — these
            lines appear the moment the control plane decides something.
          </p>
        ) : (
          events.map((event, index) => (
            <div className="live-line" key={`${event.at}-${index}`}>
              <span className="live-time mono">
                {new Date(event.at).toLocaleTimeString()}
              </span>
              <Badge tone={TONE[event.kind]}>{event.kind}</Badge>
              <span className="live-text">{event.text}</span>
            </div>
          ))
        )}
      </div>
    </Card>
  );
}
