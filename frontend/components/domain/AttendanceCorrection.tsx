"use client";

import { useState } from "react";
import { Badge, Boundary, Field, Modal, Spinner } from "@/components/ui";
import { useAction, useQuery } from "@/lib/hooks";
import { attendanceApi, type AttendanceStatus } from "@/lib/attendance-api";
import { formatDateTime, formatDayShort } from "@/lib/format";

const SOURCE_LABEL = { SYSTEM: "Default", CUSTOMER: "Customer", OWNER_CORRECTION: "Staff correction" } as const;

/** Correct one day and see who changed it before. History is append-only on the server. */
export function AttendanceCorrection({
  providerId,
  subscriptionId,
  customerName,
  date,
  current,
  canManage,
  onClose,
  onSaved,
}: {
  providerId: string;
  subscriptionId: string;
  customerName: string;
  date: string;
  current: AttendanceStatus;
  canManage: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [status, setStatus] = useState<AttendanceStatus>(current === "PRESENT" ? "ABSENT" : "PRESENT");
  const [reason, setReason] = useState("");
  const history = useQuery((t) => attendanceApi.history(t, providerId, subscriptionId, date), [providerId, subscriptionId, date]);
  const { run, busy } = useAction();

  async function save() {
    const saved = await run((t) => attendanceApi.correct(t, providerId, subscriptionId, date, status, reason.trim()), { success: "Attendance updated." });
    if (saved !== undefined) {
      onSaved();
      onClose();
    }
  }

  return (
    <Modal title={`${customerName} · ${formatDayShort(date)}`} onClose={onClose}>
      <div className="row">
        <span className="muted small">Currently</span>
        <Badge tone={current === "PRESENT" ? "ok" : "danger"}>{current === "PRESENT" ? "Present" : "Absent"}</Badge>
      </div>

      {canManage ? (
        <>
          <div className="field">
            <span className="label">Mark as</span>
            <div className="seg" role="group" aria-label="Attendance status">
              <button type="button" aria-pressed={status === "PRESENT"} onClick={() => setStatus("PRESENT")}>Present</button>
              <button type="button" aria-pressed={status === "ABSENT"} onClick={() => setStatus("ABSENT")}>Absent</button>
            </div>
          </div>
          <Field label="Reason" hint="Required - it's kept in the history.">
            {(id) => <input id={id} className="input" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Customer called to say…" />}
          </Field>
          <div className="modal-actions">
            <button className="btn" onClick={onClose}>Close</button>
            <button className="btn btn-primary" disabled={busy || !reason.trim() || status === current} onClick={() => void save()}>
              {busy ? <Spinner /> : null} Save correction
            </button>
          </div>
        </>
      ) : null}

      <hr className="divider" />
      <h3>History</h3>
      <Boundary query={history} rows={2}>
        {(entries) =>
          entries.length === 0 ? (
            <p className="small muted">No changes recorded - counted as present by default.</p>
          ) : (
            <ul className="timeline">
              {entries.map((h) => (
                <li key={h.id}>
                  <div className="row" style={{ gap: "0.4rem" }}>
                    <Badge tone={h.status === "PRESENT" ? "ok" : "danger"}>{h.status === "PRESENT" ? "Present" : "Absent"}</Badge>
                    <span className="small">{SOURCE_LABEL[h.source]}</span>
                    {h.current ? <Badge tone="info">Current</Badge> : null}
                  </div>
                  <div className="small muted">{formatDateTime(h.recordedAt)}{h.reason ? ` · “${h.reason}”` : ""}</div>
                </li>
              ))}
            </ul>
          )
        }
      </Boundary>
    </Modal>
  );
}
