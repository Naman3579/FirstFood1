"use client";

import { useMemo } from "react";
import { Pager } from "@/components/ui";
import { addDays, parseDay, toDay, todayInIndia } from "@/lib/format";

export type CalCell = { status: "PRESENT" | "ABSENT"; locked?: boolean };

const DOW = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** "2026-10" for a day string. */
export function monthOf(day: string): string {
  return day.slice(0, 7);
}

export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1 + delta, 1));
  return toDay(d).slice(0, 7);
}

export function monthBounds(month: string): { first: string; last: string } {
  const first = `${month}-01`;
  return { first, last: addDays(shiftMonth(month, 1) + "-01", -1) };
}

/**
 * Month grid, Monday first. Days outside [rangeStart, rangeEnd] (the subscription's life) are dimmed and not
 * selectable. What each day MEANS (present/absent/locked) comes from the server and is only painted here.
 */
export function MonthCalendar({
  month,
  onMonth,
  cells,
  selected,
  onSelect,
  rangeStart,
  rangeEnd,
  minMonth,
  maxMonth,
}: {
  month: string;
  onMonth: (m: string) => void;
  cells: Map<string, CalCell>;
  selected: string | null;
  onSelect: (day: string) => void;
  rangeStart: string;
  rangeEnd: string | null;
  minMonth?: string;
  maxMonth?: string;
}) {
  const today = todayInIndia();
  const weeks = useMemo(() => {
    const { first, last } = monthBounds(month);
    const lead = (parseDay(first).getUTCDay() + 6) % 7; // Monday = 0
    const total = lead + parseDay(last).getUTCDate();
    const out: (string | null)[] = [];
    for (let i = 0; i < Math.ceil(total / 7) * 7; i++) {
      const dayNo = i - lead + 1;
      out.push(dayNo >= 1 && i < total ? `${month}-${String(dayNo).padStart(2, "0")}` : null);
    }
    return out;
  }, [month]);

  const label = parseDay(`${month}-01`).toLocaleDateString("en-IN", { month: "long", year: "numeric", timeZone: "UTC" });

  return (
    <div>
      <div className="cal-head">
        <Pager
          label={label}
          onPrev={() => (!minMonth || month > minMonth) && onMonth(shiftMonth(month, -1))}
          onNext={() => (!maxMonth || month < maxMonth) && onMonth(shiftMonth(month, 1))}
        />
        {monthOf(today) !== month ? (
          <button className="btn btn-sm btn-ghost" onClick={() => onMonth(monthOf(today))}>
            Today
          </button>
        ) : null}
      </div>
      <div className="cal" role="grid" aria-label={label}>
        {DOW.map((d) => (
          <div key={d} className="cal-dow" role="columnheader">
            {d}
          </div>
        ))}
        {weeks.map((day, i) => {
          if (!day) return <div key={`b${i}`} />;
          const inRange = day >= rangeStart && (rangeEnd === null || day <= rangeEnd);
          const cell = inRange ? cells.get(day) : undefined;
          return (
            <button
              key={day}
              type="button"
              className="cal-day"
              data-out={!inRange}
              data-status={cell?.status}
              data-today={day === today}
              data-selected={selected === day}
              data-locked={cell?.locked ? true : undefined}
              disabled={!inRange}
              aria-pressed={selected === day}
              aria-label={`${day}${cell ? `, ${cell.status.toLowerCase()}` : ""}`}
              onClick={() => onSelect(day)}
            >
              {Number(day.slice(8))}
            </button>
          );
        })}
      </div>
      <div className="legend" style={{ marginTop: "0.9rem" }}>
        <span>
          <i style={{ background: "var(--ok-soft)", border: "1px solid var(--ok)" }} />
          Eating
        </span>
        <span>
          <i style={{ background: "var(--danger-soft)", border: "1px solid var(--danger)" }} />
          Not eating
        </span>
        <span>
          <i style={{ background: "var(--surface-2)", border: "1px solid var(--primary)" }} />
          Today
        </span>
        <span>• Dot = locked</span>
      </div>
    </div>
  );
}
