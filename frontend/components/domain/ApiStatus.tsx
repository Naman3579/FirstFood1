"use client";

import { useEffect, useState } from "react";
import { systemApi, type VersionInfo } from "@/lib/system-api";

/** Tiny "is the server reachable, and which API is this" indicator (GET /api/v1/version, public). */
export function ApiStatus() {
  const [state, setState] = useState<{ kind: "checking" } | { kind: "up"; info: VersionInfo } | { kind: "down" }>({ kind: "checking" });

  useEffect(() => {
    let cancelled = false;
    systemApi
      .version()
      .then((info) => !cancelled && setState({ kind: "up", info }))
      .catch(() => !cancelled && setState({ kind: "down" }));
    return () => {
      cancelled = true;
    };
  }, []);

  const label = state.kind === "checking" ? "Checking server…" : state.kind === "up" ? `Server online · API ${state.info.apiVersion}` : "Server unreachable";
  const color = state.kind === "up" ? "var(--ok)" : state.kind === "down" ? "var(--danger)" : "var(--faint)";

  return (
    <span className="small faint row" style={{ gap: "0.4rem", justifyContent: "center" }} role="status">
      <span aria-hidden="true" style={{ width: "0.5rem", height: "0.5rem", borderRadius: "50%", background: color, display: "inline-block" }} />
      {label}
    </span>
  );
}
