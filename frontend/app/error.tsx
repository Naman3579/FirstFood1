"use client";

import { useEffect } from "react";
import { TriangleAlert } from "lucide-react";

/**
 * App-level error boundary. Deliberately does not render error.message to the user: a backend or framework error
 * could contain internal details that must not reach the browser. Full detail goes to the console only.
 */
export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    // Replace with real error tracking (Sentry etc.) once one is wired up.
    console.error(error);
  }, [error]);

  return (
    <div className="page" style={{ alignItems: "center", textAlign: "center", paddingTop: "4rem", maxWidth: "30rem" }}>
      <div className="empty-icon" style={{ background: "var(--danger-soft)", color: "var(--danger)" }}>
        <TriangleAlert size={26} />
      </div>
      <h1>Something went wrong</h1>
      <p className="muted">Please try again. If the problem continues, contact support.</p>
      <button className="btn btn-primary" onClick={reset}>
        Try again
      </button>
    </div>
  );
}
