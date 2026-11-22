"use client";

import { useMemo, useState } from "react";
import { CalendarCheck2, Check, ChevronLeft, ChevronRight, UserCheck, UserX, Users } from "lucide-react";
import { Avatar, Badge, Boundary, Empty, PageHeader, SearchBox, Segmented, StatTile } from "@/components/ui";
import { AttendanceCorrection } from "@/components/domain/AttendanceCorrection";
import { useProvider } from "@/components/shell/ProviderShell";
import { useQuery } from "@/lib/hooks";
import { attendanceApi, type DailySheetRow } from "@/lib/attendance-api";
import { addDays, formatDayShort, todayInIndia } from "@/lib/format";

type View = "ALL" | "ABSENT" | "PRESENT";

export default function AttendancePage() {
  const { providerId, can } = useProvider();
  const today = todayInIndia();
  const [date, setDate] = useState(today);
  const [view, setView] = useState<View>("ALL");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<DailySheetRow | null>(null);
  const sheet = useQuery((t) => attendanceApi.dailySheet(t, providerId, date), [providerId, date]);

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (sheet.data?.rows ?? [])
      .filter((r) => view === "ALL" || r.status === view)
      .filter((r) => !needle || r.customerName.toLowerCase().includes(needle))
      .sort((a, b) => (a.status === b.status ? a.customerName.localeCompare(b.customerName) : a.status === "ABSENT" ? -1 : 1));
  }, [sheet.data, view, q]);

  return (
    <main className="page">
      <PageHeader title="Attendance" sub="Everyone counts as eating unless they (or you) say otherwise." />

      <div className="filter-bar">
        <div className="row" style={{ gap: "0.25rem" }}>
          <button className="btn btn-icon btn-sm" aria-label="Previous day" onClick={() => setDate(addDays(date, -1))}><ChevronLeft size={18} /></button>
          <input type="date" className="input" style={{ width: "11rem" }} value={date} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Date" />
          <button className="btn btn-icon btn-sm" aria-label="Next day" onClick={() => setDate(addDays(date, 1))}><ChevronRight size={18} /></button>
          {date !== today ? <button className="btn btn-sm btn-ghost" onClick={() => setDate(today)}>Today</button> : null}
        </div>
        <span className="strong">{formatDayShort(date)}</span>
      </div>

      <Boundary query={sheet} rows={5} skeleton={<div className="grid grid-stats">{[0, 1, 2].map((i) => <div key={i} className="skeleton" style={{ height: "5.5rem" }} />)}</div>}>
        {(s) => (
          <>
            <div className="grid grid-stats" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
              <StatTile label="Expected" value={s.expected} icon={<Users size={15} />} sub="Active subscriptions" />
              <StatTile label="Eating" value={s.present} icon={<UserCheck size={15} />} sub="Present" />
              <StatTile label="Not eating" value={s.absent} icon={<UserX size={15} />} sub="Absent" />
            </div>

            <div className="filter-bar">
              <Segmented<View>
                label="Show"
                value={view}
                onChange={setView}
                options={[
                  { value: "ALL", label: `All (${s.expected})` },
                  { value: "ABSENT", label: `Absent (${s.absent})` },
                  { value: "PRESENT", label: `Present (${s.present})` },
                ]}
              />
              <SearchBox placeholder="Search customer" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search customer" />
            </div>

            {rows.length === 0 ? (
              <div className="card">
                <Empty icon={<CalendarCheck2 size={26} />} title={s.expected === 0 ? "No one is expected" : "No matches"}>
                  {s.expected === 0 ? "There are no running subscriptions on this day." : "Try another filter."}
                </Empty>
              </div>
            ) : (
              <div className="card card-flush">
                <div className="list">
                  {rows.map((r) => (
                    <button key={r.subscriptionId} className="list-item" onClick={() => setOpen(r)}>
                      <Avatar name={r.customerName} />
                      <span className="grow">
                        <span className="strong truncate" style={{ display: "block" }}>{r.customerName}</span>
                        <span className="small muted truncate" style={{ display: "block" }}>
                          {r.planName}
                          {r.reason ? ` · “${r.reason}”` : ""}
                        </span>
                      </span>
                      {r.source === "OWNER_CORRECTION" ? <Badge tone="info">Corrected</Badge> : r.source === "CUSTOMER" ? <Badge>Declared</Badge> : null}
                      {r.status === "PRESENT" ? <Badge tone="ok"><Check size={12} /> Present</Badge> : <Badge tone="danger">Absent</Badge>}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </Boundary>

      {open ? (
        <AttendanceCorrection
          providerId={providerId}
          subscriptionId={open.subscriptionId}
          customerName={open.customerName}
          date={date}
          current={open.status}
          canManage={can("ATTENDANCE_MANAGE")}
          onClose={() => setOpen(null)}
          onSaved={() => void sheet.reload()}
        />
      ) : null}
    </main>
  );
}
