/** Display helpers. The server decides every business rule; these only format what it sent. */

const TZ = "Asia/Kolkata"; // the business calendar day the backend uses (app.business.time-zone)

/** "2026-10-12" for today in India, regardless of the browser's own time zone. */
export function todayInIndia(): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  return parts; // en-CA is already YYYY-MM-DD
}

/** "14:05" for right now in India, regardless of the browser's own time zone (24-hour, as <input type="time"> uses). */
export function nowTimeInIndia(): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone: TZ, hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date());
}

export function parseDay(day: string): Date {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1));
}

export function toDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(day: string, n: number): string {
  const d = parseDay(day);
  d.setUTCDate(d.getUTCDate() + n);
  return toDay(d);
}

export function daysBetween(a: string, b: string): number {
  return Math.round((parseDay(b).getTime() - parseDay(a).getTime()) / 86_400_000);
}

export function formatDate(day: string | null | undefined): string {
  if (!day) return "—";
  return parseDay(day).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

export function formatDayShort(day: string): string {
  return parseDay(day).toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
}

export function formatDateTime(instant: string | null | undefined): string {
  if (!instant) return "—";
  return new Date(instant).toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit",
    timeZone: TZ,
  });
}

export function formatMoney(amount: number, currency = "INR"): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: amount % 1 === 0 ? 0 : 2 }).format(amount);
}

export function initials(name: string): string {
  if (!/[A-Za-z]/.test(name)) return name.replace(/\D/g, "").slice(-2) || "?"; // a phone number: show its last two digits
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase() || "?";
}

export function titleCase(enumValue: string): string {
  const s = enumValue.toLowerCase().replace(/_/g, " ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Accepts "9876543210", "+91 98765 43210", "09876543210" and returns the +91E.164 form the API validates. */
export function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (digits.startsWith("+")) return digits;
  const only = digits.replace(/\D/g, "");
  if (only.length === 10) return `+91${only}`;
  if (only.length === 11 && only.startsWith("0")) return `+91${only.slice(1)}`;
  if (only.length === 12 && only.startsWith("91")) return `+${only}`;
  return `+${only}`;
}

export function isValidPhone(phone: string): boolean {
  return /^\+?[1-9]\d{9,14}$/.test(phone);
}

/**
 * F-05: the backend sends OTP SMS only to the countries on its allow-list (default: India, +91), and only to a real
 * Indian mobile shape: 10 national digits starting 6-9. This mirrors that default so a person gets an instant, clear
 * message instead of a round trip. The server stays the authority (its list is configuration); if it is ever widened,
 * widen this too.
 */
export function isSmsSupportedPhone(phone: string): boolean {
  return /^\+?91[6-9]\d{9}$/.test(phone);
}

export const SMS_REGION_MESSAGE = "FirstFood currently supports Indian mobile numbers only. Enter a 10-digit number starting with 6, 7, 8 or 9.";

export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return "—";
  return phone.length > 6 ? `${phone.slice(0, 3)} ${phone.slice(3, 8)} ${phone.slice(8)}`.trim() : phone;
}

/** Canonical form the backend stores and compares: trimmed and lower-cased. */
export function normalizeEmail(raw: string): string {
  return raw.trim().toLowerCase();
}

/** Same shape the backend accepts (something@something.tld, no spaces). The emailed code is the real check. */
export function isValidEmail(email: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) && email.length <= 255;
}

/**
 * The one box on the sign-in screen takes an email OR a mobile number. An "@" means email; otherwise anything made of
 * digits (and the usual +, spaces, dashes, brackets) is a phone; anything else is treated as an unfinished email so the
 * person is told to enter a valid address rather than a valid number.
 */
export function detectIdentifierKind(raw: string): "email" | "phone" | "empty" {
  const t = raw.trim();
  if (!t) return "empty";
  if (t.includes("@")) return "email";
  return /^[\d+\s\-()]+$/.test(t) ? "phone" : "email";
}
