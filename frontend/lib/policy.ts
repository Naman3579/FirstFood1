import type { SubscriptionTerms } from "./subscription-api";

export function shortTime(t: string | null): string {
  return t ? t.slice(0, 5) : "";
}

/** Plain-language rules a subscription was sold with (frozen snapshot, not the plan's current policy). */
export function describePolicyFromTerms(t: SubscriptionTerms): string[] {
  const lines: string[] = [];
  if (t.extensionAllowed) {
    lines.push(
      `Absences of ${t.minConsecutiveAbsenceDays}+ days in a row extend your plan` +
        (t.maxCalendarWindowDays ? ` (up to ${t.maxCalendarWindowDays} days from the start)` : ""),
    );
  } else {
    lines.push("Absences do not extend this plan");
  }
  if (t.sameDayAbsenceAllowed) {
    lines.push(t.absenceCutoffTime ? `Skip same-day meals until ${shortTime(t.absenceCutoffTime)}` : "Same-day absences are allowed");
  } else {
    lines.push("Same-day absences are not allowed - tell us a day ahead");
  }
  return lines;
}
