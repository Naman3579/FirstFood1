#!/usr/bin/env node
/**
 * Mock of the FirstFood v1 API for frontend development and UI tests when the Spring backend is not running.
 *
 *   npm run mock            # listens on :8080, the same port BACKEND_ORIGIN points at
 *   PORT=9090 npm run mock
 *   MOCK_ACCESS_TTL=20 npm run mock     # access tokens live 20s - exercises the refresh-and-retry path
 *
 * It mirrors the real controllers' routes, request/response shapes, role permissions and error codes
 * ({ code, message }) of the PORTALS backend: one account per phone, registered on the customer portal, the staff
 * portal or both; /auth/{customer|staff}/signin|signup; staff API under /api/v1/staff/... The OTP is always 123456.
 * Refresh tokens come back in the body (set MOCK_COOKIE=1 to exercise the HttpOnly-cookie variant instead).
 * It is NOT the source of truth: business rules here are a compact approximation of the Java services.
 *
 * Demo accounts (all seeded):
 *   +919000000001  Riya  - staff AND customer: OWNER of "Annapurna Mess", customer of "Shree Tiffin"
 *   +919000000002  Manager at Annapurna (staff only)   +919000000003  Worker at Annapurna (staff only)
 *   +919000000011  Rahul - customer only, with a running plan and a few declared absences
 * Any other phone is unregistered: sign in -> ACCOUNT_NOT_REGISTERED, then create the account.
 */
import http from "node:http";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.PORT ?? 8080);
const ACCESS_TTL_MS = Number(process.env.MOCK_ACCESS_TTL ?? 15 * 60) * 1000;
const OTP = "123456";
const COOKIE_MODE = process.env.MOCK_COOKIE === "1";
const TZ = "Asia/Kolkata";

/* ------------------------------------------------------------------ helpers */

const uid = () => randomUUID();
const now = () => new Date().toISOString();
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
const nowTime = () => new Intl.DateTimeFormat("en-GB", { timeZone: TZ, hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date());
const parse = (d) => new Date(`${d}T00:00:00Z`);
const fmt = (dt) => dt.toISOString().slice(0, 10);
const add = (d, n) => fmt(new Date(parse(d).getTime() + n * 86400000));
const diff = (a, b) => Math.round((parse(b) - parse(a)) / 86400000);

class ApiErr extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
const bad = (code, message, status = 400) => new ApiErr(status, code, message);
const notFound = (what) => new ApiErr(404, `${what.toUpperCase()}_NOT_FOUND`, `${what} not found.`);
const forbid = () => new ApiErr(403, "INSUFFICIENT_PERMISSION", "You do not have permission to perform this action.");

const PERMS = {
  OWNER: ["PROVIDER_VIEW", "PROVIDER_EDIT", "PROVIDER_CLOSE", "ROLE_VIEW", "ROLE_ASSIGN", "ROLE_REVOKE", "OWNER_TRANSFER", "MEMBERSHIP_VIEW", "MEMBERSHIP_MANAGE", "PLAN_VIEW", "PLAN_MANAGE", "SUBSCRIPTION_VIEW", "SUBSCRIPTION_MANAGE", "ATTENDANCE_VIEW", "ATTENDANCE_MANAGE", "EXTENSION_VIEW", "EXTENSION_MANAGE"],
  MANAGER: ["PROVIDER_VIEW", "PROVIDER_EDIT", "ROLE_VIEW", "MEMBERSHIP_VIEW", "MEMBERSHIP_MANAGE", "PLAN_VIEW", "SUBSCRIPTION_VIEW", "SUBSCRIPTION_MANAGE", "ATTENDANCE_VIEW", "ATTENDANCE_MANAGE", "EXTENSION_VIEW", "EXTENSION_MANAGE"],
  WORKER: ["PROVIDER_VIEW", "MEMBERSHIP_VIEW", "ATTENDANCE_VIEW", "ATTENDANCE_MANAGE"],
};

function normalizePhone(raw) {
  const p = String(raw ?? "").replace(/\s/g, "");
  if (!/^\+?[1-9]\d{9,14}$/.test(p)) throw bad("VALIDATION_ERROR", "Request failed validation.");
  return p.startsWith("+") ? p : `+${p}`;
}

/* -------------------------------------------------------------------- state */

const db = {
  accounts: [], // {id, phone, email, status, createdAt}
  persons: [], // {id, accountId, fullName, primaryPerson, createdAt}
  providers: [],
  roles: [], // {id, providerId, accountId, role, status, assignedBy, assignedAt, revokedAt, revokedBy}
  plans: [],
  memberships: [],
  invitations: [],
  subs: [],
  absences: [], // {id, subscriptionId, date, status, source, reason, declaredBy, declaredAt, cancelledAt, cancelledBy, overriddenAt, overriddenBy, overrideReason}
  records: [], // attendance history rows {id, subscriptionId, date, status, source, absenceId, recordedBy, recordedAt, reason, supersedesId, current, supersededAt}
  events: [], // extension events
  accessTokens: new Map(), // token -> {accountId, exp}
  refreshTokens: new Map(), // token -> accountId
};

const accountByPhone = (phone) => db.accounts.find((a) => a.phone === phone && a.phoneVerified !== false);
const normalizeEmail = (raw) => {
  const e = String(raw ?? "").trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) throw bad("VALIDATION_ERROR", "Request failed validation.");
  return e;
};
const accountByEmail = (email) => db.accounts.find((a) => a.emailVerified && a.email === email);
const accountById = (id) => db.accounts.find((a) => a.id === id);
const personOf = (accountId) => db.persons.find((p) => p.accountId === accountId);

function ensureAccount(phone, portals = []) {
  let a = accountByPhone(phone);
  if (!a) {
    a = { id: uid(), phone, email: null, emailVerified: false, status: "ACTIVE", createdAt: now(), customerAccessAt: null, staffAccessAt: null, fullName: null };
    db.accounts.push(a);
  }
  for (const pt of portals) a[pt === "staff" ? "staffAccessAt" : "customerAccessAt"] ??= now();
  return a;
}
const hasPortal = (a, portal) => Boolean(a?.[portal === "staff" ? "staffAccessAt" : "customerAccessAt"]);

function ensurePerson(accountId, fullName) {
  let p = personOf(accountId);
  if (!p) {
    p = { id: uid(), accountId, fullName, primaryPerson: true, address: null, createdAt: now() };
    db.persons.push(p);
  }
  return p;
}

const roleOf = (accountId, providerId) => db.roles.find((r) => r.accountId === accountId && r.providerId === providerId && r.status === "ACTIVE");

function requirePerm(accountId, providerId, perm) {
  const provider = db.providers.find((p) => p.id === providerId);
  const r = roleOf(accountId, providerId);
  if (!provider || !r) throw new ApiErr(404, "PROVIDER_NOT_FOUND", "Provider not found.");
  if (!PERMS[r.role].includes(perm)) throw forbid();
  return { provider, role: r.role };
}

const writable = (provider) => {
  if (provider.status === "CLOSED") throw new ApiErr(409, "PROVIDER_CLOSED", "This provider is closed and can no longer be modified.");
};

/* ------------------------------------------------------------------- views */

function providerView(p, accountId) {
  const r = roleOf(accountId, p.id);
  return { ...p, myRole: r.role, myPermissions: PERMS[r.role] };
}

function planView(pl) {
  return pl;
}

function subStatus(s) {
  return s.status;
}

function phaseOf(s) {
  if (s.status === "CANCELLED") return "CANCELLED";
  if (s.status === "EXPIRED") return "ENDED";
  const t = today();
  if (t < s.startDate) return "UPCOMING";
  if (s.effectiveExpiryDate && t > s.effectiveExpiryDate) return "ENDED";
  return "RUNNING";
}

function presentDaysBefore(s, upto) {
  // days from start up to (excluding) `upto` that are not absent
  let n = 0;
  const end = s.effectiveExpiryDate && s.effectiveExpiryDate < add(upto, -1) ? s.effectiveExpiryDate : add(upto, -1);
  for (let d = s.startDate; d <= end; d = add(d, 1)) if (dayRecord(s.id, d)?.status !== "ABSENT") n++;
  return n;
}

function remaining(s) {
  const phase = phaseOf(s);
  const t = today();
  let remainingDays = null;
  let remainingMeals = null;
  if (s.consumptionType === "DAY") {
    if (phase === "RUNNING") remainingDays = diff(t, s.effectiveExpiryDate) + 1;
    else if (phase === "UPCOMING") remainingDays = diff(s.startDate, s.effectiveExpiryDate) + 1;
    else remainingDays = 0;
  } else {
    const bought = s.terms.purchasedMeals;
    if (phase === "UPCOMING") remainingMeals = bought;
    else if (phase === "RUNNING") remainingMeals = Math.max(0, bought - presentDaysBefore(s, t));
    else remainingMeals = 0;
    if (s.effectiveExpiryDate && phase !== "ENDED" && phase !== "CANCELLED") remainingDays = Math.max(0, diff(phase === "UPCOMING" ? s.startDate : t, s.effectiveExpiryDate) + 1);
  }
  return { remainingDays, remainingMeals };
}

function subView(s) {
  const phase = phaseOf(s);
  const m = db.memberships.find((x) => x.id === s.membershipId);
  const person = db.persons.find((p) => p.id === m.personId);
  const renewed = db.subs.some((x) => x.renewedFromSubscriptionId === s.id && x.status !== "CANCELLED");
  return {
    id: s.id,
    providerId: s.providerId,
    membershipId: s.membershipId,
    personId: m.personId,
    customerName: person.fullName,
    planId: s.planId,
    consumptionType: s.consumptionType,
    status: s.status,
    phase,
    startDate: s.startDate,
    baseExpiryDate: s.baseExpiryDate,
    effectiveExpiryDate: s.effectiveExpiryDate,
    maximumExpiryDate: s.maximumExpiryDate,
    ...remaining(s),
    renewedFromSubscriptionId: s.renewedFromSubscriptionId ?? null,
    renewed,
    renewable: phase === "ENDED" && s.status !== "CANCELLED" && !renewed,
    cancellable: (phase === "RUNNING" || phase === "UPCOMING") && s.status === "ACTIVE",
    createdBy: s.createdBy,
    createdAt: s.createdAt,
    expiredAt: s.status === "EXPIRED" ? s.effectiveExpiryDate + "T18:30:00Z" : null,
    cancelledAt: s.cancelledAt ?? null,
    cancelledBy: s.cancelledBy ?? null,
    cancellationReason: s.cancellationReason ?? null,
    verification: verificationView(s),
    terms: s.terms,
  };
}

/* F-06: an unconfirmed request lapses after the TTL (the real backend closes it with an hourly job and ignores it for capacity
   the moment it is stale). Here the lapse is derived on read: MOCK_PENDING_TTL_HOURS=0.01 makes it easy to see. */
const PENDING_TTL_MS = Number(process.env.MOCK_PENDING_TTL_HOURS ?? 48) * 3600 * 1000;
const isStalePending = (s) => s.verification?.status === "PENDING" && Date.now() - Date.parse(s.verification.requestedAt) > PENDING_TTL_MS;
function verificationView(s) {
  const v = s.verification ?? { status: "VERIFIED", requestedAt: s.createdAt, decidedAt: s.createdAt, rejectionReason: null };
  if (isStalePending(s)) {
    return { ...v, status: "EXPIRED", decidedAt: new Date(Date.parse(v.requestedAt) + PENDING_TTL_MS).toISOString(), rejectionReason: null, expiresAt: null };
  }
  return { ...v, expiresAt: v.status === "PENDING" ? new Date(Date.parse(v.requestedAt) + PENDING_TTL_MS).toISOString() : null };
}

function mySubView(s) {
  const v = subView(s);
  const provider = db.providers.find((p) => p.id === s.providerId);
  return {
    id: v.id,
    providerId: v.providerId,
    providerName: provider.name,
    personId: v.personId,
    consumptionType: v.consumptionType,
    status: v.status,
    phase: v.phase,
    startDate: v.startDate,
    effectiveExpiryDate: v.effectiveExpiryDate,
    remainingDays: v.remainingDays,
    remainingMeals: v.remainingMeals,
    cancelledAt: v.cancelledAt,
    verification: v.verification,
    terms: v.terms,
  };
}

function customerView(m) {
  const person = db.persons.find((p) => p.id === m.personId);
  const acc = accountById(person.accountId);
  return {
    membershipId: m.id,
    providerId: m.providerId,
    personId: m.personId,
    fullName: person.fullName,
    // F-03: contact details are released only while the membership is ACTIVE (accepted and not left).
    phone: m.status === "ACTIVE" ? (acc?.phone ?? null) : null,
    address: m.status === "ACTIVE" ? (person.address ?? null) : null,
    status: m.status,
    joinedAt: m.joinedAt,
    leftAt: m.leftAt ?? null,
    addedBy: m.addedBy,
    leftBy: m.leftBy ?? null,
  };
}

function roleView(r) {
  return { id: r.id, providerId: r.providerId, accountId: r.accountId, accountPhone: accountById(r.accountId)?.phone ?? null, role: r.role, status: r.status, assignedBy: r.assignedBy ?? null, assignedAt: r.assignedAt, revokedAt: r.revokedAt ?? null, revokedBy: r.revokedBy ?? null };
}

/* -------------------------------------------------------------- attendance */

const dayRecord = (subId, date) => db.records.find((r) => r.subscriptionId === subId && r.date === date && r.current);

function dayView(s, date) {
  const r = dayRecord(s.id, date);
  if (!r) return { date, status: "PRESENT", source: "SYSTEM", assumed: true, absenceId: null, reason: null, recordedBy: null, recordedAt: null };
  return { date, status: r.status, source: r.source, assumed: false, absenceId: r.absenceId, reason: r.reason, recordedBy: r.recordedBy, recordedAt: r.recordedAt };
}

function lockedReason(s, date) {
  const t = today();
  if (s.status !== "ACTIVE" || phaseOf(s) === "ENDED") return "SUBSCRIPTION_NOT_ACTIVE";
  if (date < t) return "PAST";
  if (dayRecord(s.id, date)?.source === "OWNER_CORRECTION") return "PROVIDER_CORRECTION";
  if (date === t) {
    if (!s.terms.sameDayAbsenceAllowed) return "SAME_DAY_NOT_ALLOWED";
    if (s.terms.absenceCutoffTime && nowTime() > s.terms.absenceCutoffTime.slice(0, 5)) return "CUTOFF_PASSED";
  }
  return null;
}

function setRecord(s, date, status, source, { absenceId = null, reason = null, by = null } = {}) {
  const old = dayRecord(s.id, date);
  if (old) {
    old.current = false;
    old.supersededAt = now();
  }
  const rec = { id: uid(), subscriptionId: s.id, date, status, source, absenceId, recordedBy: by, recordedAt: now(), reason, supersedesId: old?.id ?? null, current: true, supersededAt: null };
  db.records.push(rec);
  return rec;
}

function clearRecord(s, date) {
  const old = dayRecord(s.id, date);
  if (old) {
    old.current = false;
    old.supersededAt = now();
  }
}

function declare(s, accountId, from, to, reason, source = "CUSTOMER") {
  const end = to ?? from;
  if (end < from) throw bad("DATE_RANGE_INVALID", "The end date is before the start date.");
  if (diff(from, end) > 120) throw bad("DATE_RANGE_INVALID", "That range is too long.");
  const out = [];
  let created = false;
  for (let d = from; d <= end; d = add(d, 1)) {
    if (d < s.startDate || (s.effectiveExpiryDate && d > s.effectiveExpiryDate)) throw new ApiErr(400, "DATE_OUTSIDE_SUBSCRIPTION", "That date is outside your subscription.");
    const existing = db.absences.find((a) => a.subscriptionId === s.id && a.date === d && a.status === "DECLARED");
    if (existing) {
      out.push(existing);
      continue;
    }
    const lock = lockedReason(s, d);
    if (lock === "PAST") throw new ApiErr(409, "ABSENCE_DATE_IN_PAST", "You cannot change a day that has already passed.");
    if (lock === "SAME_DAY_NOT_ALLOWED") throw new ApiErr(409, "SAME_DAY_ABSENCE_NOT_ALLOWED", "This provider does not allow same-day absences.");
    if (lock === "CUTOFF_PASSED") throw new ApiErr(409, "ABSENCE_CUTOFF_PASSED", "The cutoff time for today has passed.");
    if (lock === "PROVIDER_CORRECTION") throw new ApiErr(409, "ATTENDANCE_LOCKED_BY_PROVIDER", "This day was set by the provider.");
    if (lock) throw new ApiErr(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is not active.");
    const a = { id: uid(), subscriptionId: s.id, date: d, status: "DECLARED", source, reason: reason ?? null, declaredBy: accountId, declaredAt: now(), cancelledAt: null, cancelledBy: null, overriddenAt: null, overriddenBy: null, overrideReason: null };
    db.absences.push(a);
    setRecord(s, d, "ABSENT", source, { absenceId: a.id, reason, by: accountId });
    out.push(a);
    created = true;
  }
  return { absences: out, anyCreated: created };
}

const myAbsenceView = (a) => ({ id: a.id, subscriptionId: a.subscriptionId, date: a.date, status: a.status, source: a.source, reason: a.reason, declaredAt: a.declaredAt, cancelledAt: a.cancelledAt, overriddenAt: a.overriddenAt, overrideReason: a.overrideReason });

/* --------------------------------------------------------------- extension */

function extensionStatus(s) {
  const t = today();
  const phase = phaseOf(s);
  const countedThrough = add(t, -1);
  const base = { subscriptionId: s.id, extensionAllowed: s.terms.extensionAllowed, minConsecutiveAbsenceDays: s.terms.minConsecutiveAbsenceDays, startDate: s.startDate, baseExpiryDate: s.baseExpiryDate, effectiveExpiryDate: s.effectiveExpiryDate, maximumExpiryDate: s.maximumExpiryDate, evaluatedOn: t, countedThrough };
  const empty = { countedAbsentDays: 0, qualifyingRuns: [], eligibleAbsenceDays: 0, appliedExtensionDays: 0, pendingExtensionDays: 0, capped: false, projectedExpiryDate: s.effectiveExpiryDate, canApply: false, overApplied: false, overAppliedDays: 0, eventCount: 0 };
  let blockedReason = null;
  if (s.consumptionType === "MEAL") blockedReason = "NOT_SUPPORTED_FOR_MEAL";
  else if (!s.terms.extensionAllowed) blockedReason = "NOT_ALLOWED_BY_POLICY";
  else if (s.status === "CANCELLED") blockedReason = "SUBSCRIPTION_CANCELLED";
  else if (phase === "ENDED" && db.subs.some((x) => x.renewedFromSubscriptionId === s.id)) blockedReason = "ALREADY_RENEWED";
  else if (phase === "ENDED") blockedReason = "SUBSCRIPTION_EXPIRED";
  const evs = db.events.filter((e) => e.subscriptionId === s.id);
  const applied = evs.reduce((n, e) => n + e.appliedExtensionDays, 0);
  if (blockedReason) return { ...base, ...empty, appliedExtensionDays: applied, eventCount: evs.length, blockedReason };

  const absentDays = db.absences.filter((a) => a.subscriptionId === s.id && a.status === "DECLARED" && a.date <= countedThrough && a.date >= s.startDate).map((a) => a.date).sort();
  const runs = [];
  for (const d of absentDays) {
    const last = runs[runs.length - 1];
    if (last && add(last.to, 1) === d) {
      last.to = d;
      last.days++;
    } else runs.push({ from: d, to: d, days: 1 });
  }
  const min = s.terms.minConsecutiveAbsenceDays ?? 1;
  const qualifying = runs.filter((r) => r.days >= min);
  const eligible = qualifying.reduce((n, r) => n + r.days, 0);
  const wanted = Math.max(0, eligible - applied);
  const room = s.maximumExpiryDate ? Math.max(0, diff(s.effectiveExpiryDate, s.maximumExpiryDate)) : Infinity;
  const pending = Math.min(wanted, room);
  return {
    ...base,
    blockedReason: null,
    countedAbsentDays: absentDays.length,
    qualifyingRuns: qualifying,
    eligibleAbsenceDays: eligible,
    appliedExtensionDays: applied,
    pendingExtensionDays: pending,
    capped: wanted > room,
    projectedExpiryDate: add(s.effectiveExpiryDate, pending),
    canApply: pending > 0,
    overApplied: applied > eligible,
    overAppliedDays: Math.max(0, applied - eligible),
    eventCount: evs.length,
  };
}

const eventView = (e) => e;
const myEventView = ({ subscriptionId, createdBy, evaluatedOn, requestedExtensionDays, ...rest }) => rest;

/* ------------------------------------------------------------------- seed */

function seed() {
  const T = today();
  const riya = ensureAccount("+919000000001", ["staff", "customer"]);
  riya.fullName = "Riya Khandelwal";
  const mgr = ensureAccount("+919000000002", ["staff"]);
  mgr.fullName = "Mohan Patil";
  const wrk = ensureAccount("+919000000003", ["staff"]);
  wrk.fullName = "Sunita Kale";
  const owner2 = ensureAccount("+919000000009", ["staff"]);
  owner2.fullName = "Dev Malhotra";
  ensurePerson(riya.id, "Riya Khandelwal");
  riya.email = "riya@example.com";

  const mk = (name, extra = {}) => {
    const p = { id: uid(), name, providerTypes: ["MESS"], description: null, addressLine: "12 Garden Road", locality: "Kothrud", city: "Pune", pincode: "411038", contactPhone: "+919800000000", status: "ACTIVE", acceptingNewCustomers: true, maxActiveSubscriptions: 60, createdAt: now(), updatedAt: now(), closedAt: null, closedByAccountId: null, closureReason: null, ...extra };
    db.providers.push(p);
    return p;
  };
  const ann = mk("Annapurna Mess", { description: "Home-style North & South Indian thali. Lunch 12-3, dinner 7-10." });
  const shree = mk("Shree Tiffin", { providerTypes: ["TIFFIN", "MESS"], locality: "Baner", maxActiveSubscriptions: null, description: "Daily tiffin delivery to offices in Baner." });
  const addRole = (p, acc, role) => db.roles.push({ id: uid(), providerId: p.id, accountId: acc.id, role, status: "ACTIVE", assignedBy: riya.id, assignedAt: now(), revokedAt: null, revokedBy: null });
  addRole(ann, riya, "OWNER");
  addRole(ann, mgr, "MANAGER");
  addRole(ann, wrk, "WORKER");
  addRole(shree, owner2, "OWNER");

  const plan = (p, name, description, type, qty, price, policy, status = "ACTIVE") => {
    const pl = { id: uid(), providerId: p.id, name, description, consumptionType: type, durationDays: type === "DAY" ? qty : null, mealQuantity: type === "MEAL" ? qty : null, price, currency: "INR", status, policy: { version: 1, effectiveFrom: now(), ...policy }, createdBy: riya.id, updatedBy: riya.id, createdAt: now(), updatedAt: now() };
    db.plans.push(pl);
    return pl;
  };
  const monthly = plan(ann, "Monthly Lunch & Dinner", "Both meals, every day for 30 days.", "DAY", 30, 2500, { extensionAllowed: true, minConsecutiveAbsenceDays: 2, sameDayAbsenceAllowed: true, absenceCutoffTime: "23:59:00", maxCalendarWindowDays: 45 });
  const meals = plan(ann, "Lunch 60-meal pack", "Use your 60 lunches whenever you like within 90 days.", "MEAL", 60, 4200, { extensionAllowed: false, minConsecutiveAbsenceDays: null, sameDayAbsenceAllowed: true, absenceCutoffTime: null, maxCalendarWindowDays: 90 });
  const weekly = plan(ann, "Weekly trial", "Try us for a week.", "DAY", 7, 700, { extensionAllowed: false, minConsecutiveAbsenceDays: null, sameDayAbsenceAllowed: false, absenceCutoffTime: null, maxCalendarWindowDays: null });
  plan(ann, "Festival special (retired)", null, "DAY", 10, 1200, { extensionAllowed: false, minConsecutiveAbsenceDays: null, sameDayAbsenceAllowed: true, absenceCutoffTime: null, maxCalendarWindowDays: null }, "INACTIVE");
  const tiffin = plan(shree, "Office tiffin - 26 days", "Mon-Sat lunch tiffin.", "DAY", 26, 2900, { extensionAllowed: true, minConsecutiveAbsenceDays: 3, sameDayAbsenceAllowed: true, absenceCutoffTime: "08:00:00", maxCalendarWindowDays: 40 });

  const people = [
    ["+919000000011", "Rahul Sharma"],
    ["+919000000012", "Priya Nair"],
    ["+919000000013", "Aman Verma"],
    ["+919000000014", "Sneha Kulkarni"],
    ["+919000000015", "Karthik Iyer"],
    ["+919000000016", "Meera Joshi"],
  ];
  const mem = {};
  for (const [phone, name] of people) {
    const acc = ensureAccount(phone, ["customer"]);
    acc.fullName = name;
    const person = ensurePerson(acc.id, name);
    const m = { id: uid(), providerId: ann.id, personId: person.id, status: "ACTIVE", joinedAt: now(), leftAt: null, addedBy: riya.id, leftBy: null };
    db.memberships.push(m);
    mem[name.split(" ")[0]] = m;
  }
  // Riya is also a customer at Shree Tiffin.
  const riyaMem = { id: uid(), providerId: shree.id, personId: personOf(riya.id).id, status: "ACTIVE", joinedAt: now(), leftAt: null, addedBy: owner2.id, leftBy: null };
  db.memberships.push(riyaMem);

  const sell = (m, p, pl, start, extra = {}) => {
    const days = pl.consumptionType === "DAY" ? pl.durationDays : null;
    const pol = pl.policy;
    const base = days ? add(start, days - 1) : pol.maxCalendarWindowDays ? add(start, pol.maxCalendarWindowDays - 1) : null;
    const s = {
      id: uid(), providerId: p.id, membershipId: m.id, planId: pl.id, consumptionType: pl.consumptionType, status: "ACTIVE", startDate: start,
      baseExpiryDate: base, effectiveExpiryDate: base, maximumExpiryDate: pol.maxCalendarWindowDays ? add(start, pol.maxCalendarWindowDays - 1) : null,
      createdBy: riya.id, createdAt: now(),
      terms: { planName: pl.name, price: pl.price, currency: "INR", purchasedDays: days, purchasedMeals: pl.mealQuantity, policyVersion: pol.version, extensionAllowed: pol.extensionAllowed, minConsecutiveAbsenceDays: pol.minConsecutiveAbsenceDays, sameDayAbsenceAllowed: pol.sameDayAbsenceAllowed, absenceCutoffTime: pol.absenceCutoffTime, maxCalendarWindowDays: pol.maxCalendarWindowDays, capturedAt: now() },
      ...extra,
    };
    db.subs.push(s);
    return s;
  };

  const rahul = sell(mem.Rahul, ann, monthly, add(T, -12));
  const priya = sell(mem.Priya, ann, monthly, add(T, -27));
  sell(mem.Aman, ann, monthly, add(T, -45), { status: "EXPIRED" });
  sell(mem.Sneha, ann, weekly, add(T, 3), { verification: { status: "PENDING", requestedAt: now(), decidedAt: null, rejectionReason: null } });
  sell(mem.Karthik, ann, meals, add(T, -20));
  sell(mem.Meera, ann, monthly, add(T, -9), { status: "CANCELLED", cancelledAt: add(T, -5) + "T10:00:00Z", cancelledBy: riya.id, cancellationReason: "Moved to another city" });
  sell(riyaMem, shree, tiffin, add(T, -8));

  // Rahul: away 3 days in a row last week -> qualifies for an extension; plus one planned absence ahead.
  // The seed predates "today" for those days, so bypass the PAST check by writing directly:
}

// Seed absences in the past directly (the public declare() rightly refuses past days).
function seedPastAbsences() {
  const T = today();
  const rahulAcc = accountByPhone("+919000000011");
  const sub = db.subs.find((s) => db.memberships.find((m) => m.id === s.membershipId && personOf(rahulAcc.id)?.id === m.personId));
  for (const off of [-6, -5, -4]) {
    const d = add(T, off);
    if (db.absences.some((a) => a.subscriptionId === sub.id && a.date === d)) continue;
    const a = { id: uid(), subscriptionId: sub.id, date: d, status: "DECLARED", source: "CUSTOMER", reason: "Family function", declaredBy: rahulAcc.id, declaredAt: now(), cancelledAt: null, cancelledBy: null, overriddenAt: null, overriddenBy: null, overrideReason: null };
    db.absences.push(a);
    setRecord(sub, d, "ABSENT", "CUSTOMER", { absenceId: a.id, reason: "Family function", by: rahulAcc.id });
  }
  // Priya skips tomorrow
  const priyaAcc = accountByPhone("+919000000012");
  const ps = db.subs.find((s) => db.memberships.find((m) => m.id === s.membershipId && personOf(priyaAcc.id)?.id === m.personId));
  try { declare(ps, priyaAcc.id, add(T, 1), add(T, 1), "Out of town"); } catch { /* ignore */ }
}

/* ----------------------------------------------------------------- routing */

const routes = [];
const route = (method, pattern, handler, { auth = true } = {}) => {
  const keys = [];
  const re = new RegExp("^" + pattern.replace(/\{(\w+)\}/g, (_, k) => (keys.push(k), "([^/]+)")) + "$");
  routes.push({ method, re, keys, handler, auth });
};

const ok = (body, status = 200) => ({ status, body });
const noContent = () => ({ status: 204 });

/* auth */
route("GET", "/api/v1/version", () => ok({ service: "firstfood-v2", apiVersion: "v1" }), { auth: false });

// F-05, same rule as the real backend's default (SmsDestinationPolicy): +91 and a 10-digit mobile starting 6-9.
// A pure function of the number - it never reveals whether an account exists.
const smsAllowed = (phone) => /^\+?91[6-9]\d{9}$/.test(phone);

route("POST", "/api/v1/auth/otp/request", ({ body }) => {
  const phone = normalizePhone(body?.phone);
  if (!smsAllowed(phone)) {
    throw bad("PHONE_REGION_NOT_SUPPORTED", "FirstFood currently supports Indian mobile numbers only. Enter a 10-digit number starting with 6, 7, 8 or 9.");
  }
  console.log(`[DEV OTP] phone=${phone} otp=${OTP}`);
  return { status: 202 };
}, { auth: false });

function issue(accountId, cookieMode) {
  const access = "acc_" + uid();
  db.accessTokens.set(access, { accountId, exp: Date.now() + ACCESS_TTL_MS });
  const refresh = "ref_" + uid();
  db.refreshTokens.set(refresh, accountId);
  const res = ok({ accessToken: access, refreshToken: cookieMode ? null : refresh });
  if (cookieMode) res.cookie = `ff_refresh=${refresh}; HttpOnly; Path=/api/v1/auth; SameSite=Strict; Max-Age=2592000`;
  return res;
}

const checkOtp = (body) => {
  const phone = normalizePhone(body?.phone);
  if (!/^\d{6}$/.test(body?.code ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
  if (body.code !== OTP) throw new ApiErr(401, "INVALID_OTP", "The code entered is incorrect.");
  return phone;
};
for (const portal of ["customer", "staff"]) {
  route("POST", `/api/v1/auth/${portal}/signin`, ({ body, cookieMode }) => {
    const phone = checkOtp(body);
    const acc = accountByPhone(phone);
    if (!hasPortal(acc, portal)) throw new ApiErr(404, "ACCOUNT_NOT_REGISTERED", `No ${portal} account exists for this number. Create one to continue.`);
    return issue(acc.id, cookieMode && COOKIE_MODE);
  }, { auth: false });
  route("POST", `/api/v1/auth/${portal}/signup`, ({ body, cookieMode }) => {
    const phone = checkOtp(body);
    const name = String(body?.fullName ?? "").trim();
    if (!name || name.length > 120) throw bad("VALIDATION_ERROR", "Request failed validation.");
    const existing = accountByPhone(phone);
    if (hasPortal(existing, portal)) throw new ApiErr(409, "ACCOUNT_ALREADY_REGISTERED", `A ${portal} account already exists for this number. Sign in instead.`);
    const acc = ensureAccount(phone, [portal]);
    acc.fullName ??= name;
    return issue(acc.id, cookieMode && COOKIE_MODE);
  }, { auth: false });
}

/* email sign-in / sign-up (same rules as the phone routes; only a VERIFIED email identifies an account) */
route("POST", "/api/v1/auth/email/otp/request", ({ body }) => {
  const email = normalizeEmail(body?.email);
  console.log(`[DEV OTP] email=${email} otp=${OTP}`);
  return { status: 202 };
}, { auth: false });
for (const portal of ["customer", "staff"]) {
  route("POST", `/api/v1/auth/${portal}/email/signin`, ({ body, cookieMode }) => {
    const email = normalizeEmail(body?.email);
    if (!/^\d{6}$/.test(body?.code ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
    if (body.code !== OTP) throw new ApiErr(401, "INVALID_OTP", "The code entered is incorrect.");
    const acc = accountByEmail(email);
    if (!hasPortal(acc, portal)) throw new ApiErr(404, "ACCOUNT_NOT_REGISTERED", `No ${portal} account exists for this email. Create one to continue.`);
    return issue(acc.id, cookieMode && COOKIE_MODE);
  }, { auth: false });
  route("POST", `/api/v1/auth/${portal}/email/signup`, ({ body, cookieMode }) => {
    const email = normalizeEmail(body?.email);
    if (!/^\d{6}$/.test(body?.code ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
    if (body.code !== OTP) throw new ApiErr(401, "INVALID_OTP", "The code entered is incorrect.");
    const name = String(body?.fullName ?? "").trim();
    if (!name || name.length > 120) throw bad("VALIDATION_ERROR", "Request failed validation.");
    let acc = accountByEmail(email);
    if (hasPortal(acc, portal)) throw new ApiErr(409, "ACCOUNT_ALREADY_REGISTERED", `A ${portal} account already exists for this email. Sign in instead.`);
    if (!acc) {
      acc = { id: uid(), phone: null, email, emailVerified: true, status: "ACTIVE", createdAt: now(), customerAccessAt: null, staffAccessAt: null, fullName: null };
      db.accounts.push(acc);
    }
    acc[portal === "staff" ? "staffAccessAt" : "customerAccessAt"] ??= now();
    acc.fullName ??= name;
    return issue(acc.id, cookieMode && COOKIE_MODE);
  }, { auth: false });
}

route("POST", "/api/v1/auth/refresh", ({ body, cookies, cookieMode }) => {
  const token = body?.refreshToken ?? cookies.ff_refresh;
  const accountId = token && db.refreshTokens.get(token);
  if (!accountId) throw new ApiErr(401, "INVALID_REFRESH_TOKEN", "This session is no longer valid. Please log in again.");
  db.refreshTokens.delete(token); // rotation
  return issue(accountId, cookieMode && COOKIE_MODE);
}, { auth: false });

route("POST", "/api/v1/auth/logout", ({ body, cookies, cookieMode }) => {
  const token = body?.refreshToken ?? cookies.ff_refresh;
  if (token) db.refreshTokens.delete(token);
  const res = noContent();
  if (cookieMode && COOKIE_MODE) res.cookie = "ff_refresh=; HttpOnly; Path=/api/v1/auth; SameSite=Strict; Max-Age=0";
  return res;
}, { auth: false });

/* me */
const profile = (a) => ({ accountId: a.id, phone: a.phone, phoneVerified: Boolean(a.phone) && a.phoneVerified !== false, email: a.email, emailVerified: a.emailVerified, fullName: a.fullName, portals: ["customer", "staff"].filter((p) => hasPortal(a, p)).map((p) => p.toUpperCase()), status: a.status, createdAt: a.createdAt });
route("GET", "/api/v1/me", ({ account }) => ok(profile(account)));
route("POST", "/api/v1/me/email/otp/request", ({ body }) => {
  if (!/^\S+@\S+\.\S+$/.test(body?.email ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
  console.log(`[DEV OTP] email=${body.email} otp=${OTP}`);
  return { status: 202 };
});
route("POST", "/api/v1/me/email/otp/verify", ({ account, body }) => {
  if (!/^\S+@\S+\.\S+$/.test(body?.email ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
  if (body?.code !== OTP) throw new ApiErr(401, "INVALID_OTP", "The code entered is incorrect.");
  account.email = body.email;
  account.emailVerified = true;
  return ok(profile(account));
});
route("PUT", "/api/v1/me/phone", ({ account, body }) => {
  const phone = normalizePhone(body?.phone);
  if (account.phone && account.phoneVerified !== false) throw new ApiErr(409, "PHONE_VERIFICATION_REQUIRED", "This phone number must be verified with a code.");
  const other = accountByPhone(phone);
  if (other && other.id !== account.id) throw new ApiErr(409, "PHONE_ALREADY_IN_USE", "This phone number is already associated with an account.");
  account.phone = phone;
  account.phoneVerified = false;
  return ok(profile(account));
});
route("POST", "/api/v1/me/phone/otp/request", ({ body }) => {
  const phone = normalizePhone(body?.newPhone);
  if (!smsAllowed(phone)) {
    throw bad("PHONE_REGION_NOT_SUPPORTED", "FirstFood currently supports Indian mobile numbers only. Enter a 10-digit number starting with 6, 7, 8 or 9.");
  }
  if (accountByPhone(phone)) throw new ApiErr(409, "PHONE_ALREADY_IN_USE", "This phone number is already associated with an account.");
  console.log(`[DEV OTP] phone=${phone} otp=${OTP}`);
  return { status: 202 };
});
route("POST", "/api/v1/me/phone/otp/verify", ({ account, body }) => {
  const phone = normalizePhone(body?.newPhone);
  if (body?.code !== OTP) throw new ApiErr(401, "INVALID_OTP", "The code entered is incorrect.");
  if (accountByPhone(phone)) throw new ApiErr(409, "PHONE_ALREADY_IN_USE", "This phone number is already associated with an account.");
  account.phone = phone;
  account.phoneVerified = true;
  return ok(profile(account));
});
route("GET", "/api/v1/me/person", ({ account }) => {
  const p = personOf(account.id);
  if (!p) throw notFound("person");
  return ok({ id: p.id, fullName: p.fullName, primaryPerson: p.primaryPerson, address: p.address ?? null, createdAt: p.createdAt });
});
route("PUT", "/api/v1/me/person/address", ({ account, body }) => {
  const p = personOf(account.id);
  if (!p) throw notFound("person");
  const t = (v) => (typeof v === "string" && v.trim() ? v.trim() : null);
  const [line, locality, city, pincode, landmark] = [t(body?.addressLine), t(body?.locality), t(body?.city), t(body?.pincode), t(body?.landmark)];
  if (!line && !locality && !city && !pincode && !landmark) p.address = null;
  else {
    if (!line || !locality || !city) throw bad("INVALID_ADDRESS", "Enter the street address, area and city.");
    if (pincode && !/^[1-9][0-9]{5}$/.test(pincode)) throw bad("INVALID_ADDRESS", "Enter a valid 6-digit pincode.");
    p.address = { addressLine: line, locality, city, pincode, landmark };
  }
  return ok({ id: p.id, fullName: p.fullName, primaryPerson: p.primaryPerson, address: p.address, createdAt: p.createdAt });
});
route("PUT", "/api/v1/me/person", ({ account, body }) => {
  const name = String(body?.fullName ?? "").trim();
  if (!name) throw bad("VALIDATION_ERROR", "Request failed validation.");
  let p = personOf(account.id);
  if (p) p.fullName = name;
  else p = ensurePerson(account.id, name);
  return ok({ id: p.id, fullName: p.fullName, primaryPerson: p.primaryPerson, address: p.address ?? null, createdAt: p.createdAt });
});
route("GET", "/api/v1/me/memberships", ({ account }) => {
  const p = personOf(account.id);
  return ok(db.memberships.filter((m) => m.personId === p?.id).map((m) => ({ membershipId: m.id, providerId: m.providerId, providerName: db.providers.find((x) => x.id === m.providerId).name, personId: m.personId, status: m.status, joinedAt: m.joinedAt, leftAt: m.leftAt })));
});
const myMembershipView = (m) => ({ membershipId: m.id, providerId: m.providerId, providerName: db.providers.find((x) => x.id === m.providerId).name, personId: m.personId, status: m.status, joinedAt: m.joinedAt, leftAt: m.leftAt ?? null });
const myInvitation = (account, id) => {
  const inv = db.invitations.find((i) => i.id === id && i.phone === normalizePhone(account.phone));
  if (!inv) throw notFound("invitation"); // someone else's invitation reads exactly like a missing one
  if (inv.status !== "PENDING") throw new ApiErr(409, "INVITATION_NOT_PENDING", "This invitation has already been answered or has lapsed.");
  if (new Date(inv.expiresAt) <= new Date()) { inv.status = "EXPIRED"; inv.respondedAt = now(); throw new ApiErr(410, "INVITATION_EXPIRED", "This invitation has expired."); }
  return inv;
};
route("GET", "/api/v1/me/invitations", ({ account }) => ok(
  db.invitations.filter((i) => i.phone === normalizePhone(account.phone) && i.status === "PENDING" && new Date(i.expiresAt) > new Date())
    .map((i) => ({ invitationId: i.id, providerId: i.providerId, providerName: db.providers.find((p) => p.id === i.providerId)?.name ?? "(unknown provider)", invitedAt: i.createdAt, expiresAt: i.expiresAt })),
));
route("POST", "/api/v1/me/invitations/{iid}/accept", ({ account, params }) => {
  const inv = myInvitation(account, params.iid);
  const person = personOf(account.id);
  if (!person) throw bad("PERSON_NAME_REQUIRED", "Add your name to your profile before accepting.");
  let m = db.memberships.find((x) => x.providerId === inv.providerId && x.personId === person.id && x.status === "ACTIVE");
  if (!m) {
    m = { id: uid(), providerId: inv.providerId, personId: person.id, status: "ACTIVE", joinedAt: now(), leftAt: null, addedBy: inv.invitedBy, leftBy: null };
    db.memberships.push(m);
  }
  inv.status = "ACCEPTED";
  inv.respondedAt = now();
  return ok(myMembershipView(m));
});
route("POST", "/api/v1/me/invitations/{iid}/decline", ({ account, params }) => {
  const inv = myInvitation(account, params.iid);
  inv.status = "DECLINED";
  inv.respondedAt = now();
  return noContent();
});
route("POST", "/api/v1/me/memberships/{mid}/leave", ({ account, params }) => {
  const p = personOf(account.id);
  const m = db.memberships.find((x) => x.id === params.mid && x.personId === p?.id);
  if (!m) throw notFound("membership");
  if (m.status === "INACTIVE") throw new ApiErr(409, "MEMBERSHIP_ALREADY_INACTIVE", "You already left this provider.");
  m.status = "INACTIVE";
  m.leftAt = now();
  m.leftBy = account.id;
  return ok(myMembershipView(m));
});

/* providers */
const PROVIDER_TYPES = ["MESS", "TIFFIN", "PG", "HOSTEL", "CORPORATE", "OTHER"];
route("GET", "/api/v1/staff/providers", ({ account }) => ok(db.roles.filter((r) => r.accountId === account.id && r.status === "ACTIVE").map((r) => providerView(db.providers.find((p) => p.id === r.providerId), account.id))));
route("POST", "/api/v1/staff/providers", ({ account, body }) => {
  const b = body ?? {};
  if (!b.name?.trim() || !Array.isArray(b.providerTypes) || b.providerTypes.length === 0 || !b.providerTypes.every((t) => PROVIDER_TYPES.includes(t)) || !b.addressLine?.trim() || !b.locality?.trim() || !b.city?.trim()) throw bad("VALIDATION_ERROR", "Request failed validation.");
  if (b.pincode && !/^[1-9][0-9]{5}$/.test(b.pincode)) throw bad("VALIDATION_ERROR", "Request failed validation.");
  const p = { id: uid(), name: b.name.trim(), providerTypes: b.providerTypes, description: b.description ?? null, addressLine: b.addressLine, locality: b.locality, city: b.city, pincode: b.pincode ?? null, contactPhone: b.contactPhone ?? null, status: "ACTIVE", acceptingNewCustomers: true, maxActiveSubscriptions: b.maxActiveSubscriptions ?? null, createdAt: now(), updatedAt: now(), closedAt: null, closedByAccountId: null, closureReason: null };
  db.providers.push(p);
  db.roles.push({ id: uid(), providerId: p.id, accountId: account.id, role: "OWNER", status: "ACTIVE", assignedBy: account.id, assignedAt: now(), revokedAt: null, revokedBy: null });
  return ok(providerView(p, account.id), 201);
});
route("GET", "/api/v1/staff/providers/{pid}", ({ account, params }) => {
  const { provider } = requirePerm(account.id, params.pid, "PROVIDER_VIEW");
  return ok(providerView(provider, account.id));
});
const TRANSITIONS = { ACTIVE: ["FULL", "TEMPORARILY_UNAVAILABLE", "CLOSED"], FULL: ["ACTIVE", "TEMPORARILY_UNAVAILABLE", "CLOSED"], TEMPORARILY_UNAVAILABLE: ["ACTIVE", "CLOSED"], CLOSED: [] };
route("PATCH", "/api/v1/staff/providers/{pid}", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "PROVIDER_EDIT");
  writable(provider);
  const b = body ?? {};
  if (b.status && b.status !== provider.status) {
    if (b.status === "CLOSED") requirePerm(account.id, params.pid, "PROVIDER_CLOSE");
    if (!TRANSITIONS[provider.status].includes(b.status)) throw new ApiErr(409, "PROVIDER_INVALID_TRANSITION", `A provider cannot move from ${provider.status} to ${b.status}.`);
    if (b.status === "CLOSED") {
      requireStepUp(account.id, b.stepUpCode);
      if (!b.closureReason?.trim()) throw bad("PROVIDER_INVALID_REQUEST", "A reason is required to close a provider.");
      if (db.subs.some((s) => s.providerId === provider.id && ["RUNNING", "UPCOMING"].includes(phaseOf(s)))) throw new ApiErr(409, "PROVIDER_HAS_ACTIVE_SUBSCRIPTIONS", "Cancel or finish all active subscriptions before closing.");
      provider.closedAt = now();
      provider.closedByAccountId = account.id;
      provider.closureReason = b.closureReason;
    }
    provider.status = b.status;
  }
  for (const k of ["name", "providerTypes", "description", "addressLine", "locality", "city", "pincode", "contactPhone", "acceptingNewCustomers"]) if (b[k] !== undefined) provider[k] = b[k];
  if (b.unlimitedCapacity) provider.maxActiveSubscriptions = null;
  else if (b.maxActiveSubscriptions !== undefined) provider.maxActiveSubscriptions = b.maxActiveSubscriptions;
  provider.updatedAt = now();
  return ok(providerView(provider, account.id));
});

/* roles */
route("GET", "/api/v1/staff/providers/{pid}/roles", ({ account, params }) => {
  requirePerm(account.id, params.pid, "ROLE_VIEW");
  return ok(db.roles.filter((r) => r.providerId === params.pid).sort((a, b) => (a.status === b.status ? 0 : a.status === "ACTIVE" ? -1 : 1)).map(roleView));
});
route("POST", "/api/v1/staff/providers/{pid}/roles", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "ROLE_ASSIGN");
  writable(provider);
  const phone = normalizePhone(body?.phone);
  if (body?.role === "OWNER") throw new ApiErr(400, "OWNER_ASSIGNMENT_NOT_ALLOWED", "Ownership can only change through a transfer.");
  if (!["MANAGER", "WORKER"].includes(body?.role)) throw new ApiErr(400, "ROLE_INVALID", "That role is not valid.");
  const target = accountByPhone(phone);
  if (!hasPortal(target, "staff")) throw new ApiErr(404, "TARGET_ACCOUNT_NOT_FOUND", "No active account is registered with that phone number.");
  if (roleOf(target.id, params.pid)) throw new ApiErr(409, "ROLE_ALREADY_ASSIGNED", "That person already has a role here.");
  const r = { id: uid(), providerId: params.pid, accountId: target.id, role: body.role, status: "ACTIVE", assignedBy: account.id, assignedAt: now(), revokedAt: null, revokedBy: null };
  db.roles.push(r);
  return ok(roleView(r), 201);
});
route("DELETE", "/api/v1/staff/providers/{pid}/roles/{rid}", ({ account, params }) => {
  const { provider } = requirePerm(account.id, params.pid, "ROLE_REVOKE");
  writable(provider);
  const r = db.roles.find((x) => x.id === params.rid && x.providerId === params.pid);
  if (!r) throw notFound("role_assignment");
  if (r.role === "OWNER") throw new ApiErr(409, "OWNER_REVOCATION_NOT_ALLOWED", "The owner cannot be removed. Transfer ownership first.");
  if (r.status === "REVOKED") throw new ApiErr(409, "ROLE_ALREADY_REVOKED", "That role was already removed.");
  r.status = "REVOKED";
  r.revokedAt = now();
  r.revokedBy = account.id;
  return ok(roleView(r));
});
/* owner-level actions (F-08): a fresh code (mock: always 123456) and a two-step ownership offer */
const STEP_UP_CODE = "123456";
db.offers = db.offers ?? []; // {id, providerId, offeredBy, targetPhone, status, createdAt, expiresAt, respondedAt}
function requireStepUp(accountId, code) {
  if (!code) throw new ApiErr(403, "STEP_UP_REQUIRED", "Enter the verification code we texted to your phone.");
  if (code !== STEP_UP_CODE) throw new ApiErr(403, "STEP_UP_CODE_INVALID", "The verification code is incorrect.");
}
const offerView = (o) => ({ id: o.id, providerId: o.providerId, targetPhone: o.targetPhone, status: o.status === "PENDING" && Date.parse(o.expiresAt) < Date.now() ? "EXPIRED" : o.status, createdAt: o.createdAt, expiresAt: o.expiresAt, respondedAt: o.respondedAt });
route("POST", "/api/v1/staff/providers/{pid}/owner-step-up", ({ account, params }) => {
  requirePerm(account.id, params.pid, "OWNER_TRANSFER");
  console.log(`[mock] owner step-up code for ${account.id}: ${STEP_UP_CODE}`);
  return noContent();
});
route("POST", "/api/v1/staff/providers/{pid}/ownership/offers", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "OWNER_TRANSFER");
  writable(provider);
  requireStepUp(account.id, body?.code);
  const phone = normalizePhone(body?.phone);
  if (phone === account.phone) throw new ApiErr(400, "OWNERSHIP_TRANSFER_TO_SELF", "You already own this provider.");
  if (db.offers.some((o) => o.providerId === params.pid && offerView(o).status === "PENDING")) throw new ApiErr(409, "OWNERSHIP_OFFER_PENDING", "There is already an open ownership offer. Withdraw it first.");
  const o = { id: uid(), providerId: params.pid, offeredBy: account.id, targetPhone: phone, status: "PENDING", createdAt: now(), expiresAt: new Date(Date.now() + 7 * 864e5).toISOString(), respondedAt: null };
  db.offers.push(o);
  return ok(offerView(o), 201);
});
route("GET", "/api/v1/staff/providers/{pid}/ownership/offers", ({ account, params }) => {
  requirePerm(account.id, params.pid, "OWNER_TRANSFER");
  return ok(db.offers.filter((o) => o.providerId === params.pid).map(offerView));
});
route("DELETE", "/api/v1/staff/providers/{pid}/ownership/offers/{oid}", ({ account, params }) => {
  requirePerm(account.id, params.pid, "OWNER_TRANSFER");
  const o = db.offers.find((x) => x.id === params.oid && x.providerId === params.pid);
  if (!o) throw notFound("ownership_offer");
  if (offerView(o).status !== "PENDING") throw new ApiErr(409, "OWNERSHIP_OFFER_NOT_PENDING", "That offer is no longer open.");
  o.status = "WITHDRAWN";
  o.respondedAt = now();
  return ok(offerView(o));
});
const myOfferViews = (account) => db.offers.filter((o) => o.targetPhone === account.phone && offerView(o).status === "PENDING").map((o) => ({ id: o.id, providerId: o.providerId, providerName: db.providers.find((p) => p.id === o.providerId)?.name ?? "", offeredByPhoneHint: "****", createdAt: o.createdAt, expiresAt: o.expiresAt }));
route("GET", "/api/v1/staff/ownership-offers", ({ account }) => ok(myOfferViews(account)));
route("POST", "/api/v1/staff/ownership-offers/{oid}/accept", ({ account, params }) => {
  const o = db.offers.find((x) => x.id === params.oid && x.targetPhone === account.phone);
  if (!o) throw notFound("ownership_offer");
  if (offerView(o).status !== "PENDING") throw new ApiErr(409, "OWNERSHIP_OFFER_NOT_PENDING", "That offer is no longer open.");
  const oldOwner = roleOf(o.offeredBy, o.providerId);
  const mine = roleOf(account.id, o.providerId);
  if (mine) mine.status = "REVOKED";
  oldOwner.status = "REVOKED";
  const nr = { id: uid(), providerId: o.providerId, accountId: account.id, role: "OWNER", status: "ACTIVE", assignedBy: o.offeredBy, assignedAt: now(), revokedAt: null, revokedBy: null };
  const fr = { id: uid(), providerId: o.providerId, accountId: o.offeredBy, role: "MANAGER", status: "ACTIVE", assignedBy: o.offeredBy, assignedAt: now(), revokedAt: null, revokedBy: null };
  db.roles.push(nr, fr);
  o.status = "ACCEPTED";
  o.respondedAt = now();
  return ok([roleView(nr), roleView(fr)]);
});
route("POST", "/api/v1/staff/ownership-offers/{oid}/decline", ({ account, params }) => {
  const o = db.offers.find((x) => x.id === params.oid && x.targetPhone === account.phone);
  if (!o) throw notFound("ownership_offer");
  o.status = "DECLINED";
  o.respondedAt = now();
  return noContent();
});

/* plans */
const planBody = (b, type) => {
  const p = b?.policy;
  const priceOk = typeof b?.price === "number" && b.price >= 0.01;
  if (!b?.name?.trim() || !priceOk || !p || typeof p.extensionAllowed !== "boolean" || typeof p.sameDayAbsenceAllowed !== "boolean") throw bad("VALIDATION_ERROR", "Request failed validation.");
  if (type === "DAY" && !b.durationDays) throw new ApiErr(400, "PLAN_TERMS_INVALID", "A day plan needs a duration in days.");
  if (type === "MEAL" && !b.mealQuantity) throw new ApiErr(400, "PLAN_TERMS_INVALID", "A meal plan needs a number of meals.");
  if (p.extensionAllowed && !p.minConsecutiveAbsenceDays) throw new ApiErr(400, "PLAN_POLICY_INVALID", "Set how many absent days in a row earn an extension.");
};
route("GET", "/api/v1/staff/providers/{pid}/plans", ({ account, params, query }) => {
  requirePerm(account.id, params.pid, "PLAN_VIEW");
  return ok(db.plans.filter((p) => p.providerId === params.pid && (query.includeInactive === "true" || p.status === "ACTIVE")).map(planView));
});
route("POST", "/api/v1/staff/providers/{pid}/plans", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "PLAN_MANAGE");
  writable(provider);
  planBody(body, body?.consumptionType);
  if (db.plans.some((p) => p.providerId === params.pid && p.name.toLowerCase() === body.name.trim().toLowerCase())) throw new ApiErr(409, "PLAN_NAME_IN_USE", "You already have a plan with that name.");
  const pl = { id: uid(), providerId: params.pid, name: body.name.trim(), description: body.description ?? null, consumptionType: body.consumptionType, durationDays: body.consumptionType === "DAY" ? body.durationDays : null, mealQuantity: body.consumptionType === "MEAL" ? body.mealQuantity : null, price: body.price, currency: "INR", status: "ACTIVE", policy: { version: 1, effectiveFrom: now(), extensionAllowed: body.policy.extensionAllowed, minConsecutiveAbsenceDays: body.policy.minConsecutiveAbsenceDays ?? null, sameDayAbsenceAllowed: body.policy.sameDayAbsenceAllowed, absenceCutoffTime: body.policy.absenceCutoffTime ? body.policy.absenceCutoffTime + ":00" : null, maxCalendarWindowDays: body.policy.maxCalendarWindowDays ?? null }, createdBy: account.id, updatedBy: account.id, createdAt: now(), updatedAt: now() };
  db.plans.push(pl);
  return ok(planView(pl), 201);
});
route("GET", "/api/v1/staff/providers/{pid}/plans/{id}", ({ account, params }) => {
  requirePerm(account.id, params.pid, "PLAN_VIEW");
  const pl = db.plans.find((p) => p.id === params.id && p.providerId === params.pid);
  if (!pl) throw notFound("plan");
  return ok(planView(pl));
});
route("PUT", "/api/v1/staff/providers/{pid}/plans/{id}", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "PLAN_MANAGE");
  writable(provider);
  const pl = db.plans.find((p) => p.id === params.id && p.providerId === params.pid);
  if (!pl) throw notFound("plan");
  planBody(body, pl.consumptionType);
  if (db.plans.some((p) => p.id !== pl.id && p.providerId === params.pid && p.name.toLowerCase() === body.name.trim().toLowerCase())) throw new ApiErr(409, "PLAN_NAME_IN_USE", "You already have a plan with that name.");
  Object.assign(pl, { name: body.name.trim(), description: body.description ?? null, price: body.price, durationDays: pl.consumptionType === "DAY" ? body.durationDays : null, mealQuantity: pl.consumptionType === "MEAL" ? body.mealQuantity : null, updatedAt: now(), updatedBy: account.id });
  pl.policy = { version: pl.policy.version + 1, effectiveFrom: now(), extensionAllowed: body.policy.extensionAllowed, minConsecutiveAbsenceDays: body.policy.minConsecutiveAbsenceDays ?? null, sameDayAbsenceAllowed: body.policy.sameDayAbsenceAllowed, absenceCutoffTime: body.policy.absenceCutoffTime ? body.policy.absenceCutoffTime.slice(0, 5) + ":00" : null, maxCalendarWindowDays: body.policy.maxCalendarWindowDays ?? null };
  return ok(planView(pl));
});
for (const [action, to, errCode, errMsg] of [["activate", "ACTIVE", "PLAN_ALREADY_ACTIVE", "This plan is already active."], ["deactivate", "INACTIVE", "PLAN_ALREADY_INACTIVE", "This plan is already inactive."]]) {
  route("POST", `/api/v1/providers/{pid}/plans/{id}/${action}`, ({ account, params }) => {
    const { provider } = requirePerm(account.id, params.pid, "PLAN_MANAGE");
    writable(provider);
    const pl = db.plans.find((p) => p.id === params.id && p.providerId === params.pid);
    if (!pl) throw notFound("plan");
    if (pl.status === to) throw new ApiErr(409, errCode, errMsg);
    pl.status = to;
    pl.updatedAt = now();
    return ok(planView(pl));
  });
}

/* customers */
route("GET", "/api/v1/staff/providers/{pid}/customers", ({ account, params, query }) => {
  requirePerm(account.id, params.pid, "MEMBERSHIP_VIEW");
  return ok(db.memberships.filter((m) => m.providerId === params.pid && (query.includeInactive === "true" || m.status === "ACTIVE")).map(customerView));
});
/* F-03: enrolment is an INVITATION keyed by phone. The response is identical for registered and unregistered numbers. */
const INVITE_TTL_MS = 7 * 24 * 3600 * 1000;
const invitationView = (i) => ({
  invitationId: i.id, providerId: i.providerId, phone: i.phone,
  status: i.status === "PENDING" && new Date(i.expiresAt) <= new Date() ? "EXPIRED" : i.status,
  createdAt: i.createdAt, expiresAt: i.expiresAt, respondedAt: i.respondedAt ?? null,
});
route("POST", "/api/v1/staff/providers/{pid}/customers", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "MEMBERSHIP_MANAGE");
  writable(provider);
  if (!provider.acceptingNewCustomers) throw new ApiErr(409, "PROVIDER_NOT_ACCEPTING_CUSTOMERS", "This provider is not accepting new customers right now.");
  const phone = normalizePhone(body?.phone);
  if (!phone) throw bad("VALIDATION_ERROR", "Request failed validation.");
  // The only thing this may reveal: the number is already a member of THIS provider.
  const acc = accountByPhone(phone);
  const person = acc ? personOf(acc.id) : null;
  if (person && db.memberships.some((m) => m.providerId === params.pid && m.personId === person.id && m.status === "ACTIVE")) {
    throw new ApiErr(409, "MEMBERSHIP_ALREADY_ACTIVE", "This customer is already on your list.");
  }
  const open = db.invitations.find((i) => i.providerId === params.pid && i.phone === phone && i.status === "PENDING" && new Date(i.expiresAt) > new Date());
  if (open) return ok(invitationView(open), 202);
  const inv = { id: uid(), providerId: params.pid, phone, invitedBy: account.id, status: "PENDING", createdAt: now(), expiresAt: new Date(Date.now() + INVITE_TTL_MS).toISOString(), respondedAt: null };
  db.invitations.push(inv);
  return ok(invitationView(inv), 202);
});
route("GET", "/api/v1/staff/providers/{pid}/customers/invitations", ({ account, params }) => {
  requirePerm(account.id, params.pid, "MEMBERSHIP_VIEW");
  return ok(db.invitations.filter((i) => i.providerId === params.pid).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(invitationView));
});
route("POST", "/api/v1/staff/providers/{pid}/customers/invitations/{iid}/withdraw", ({ account, params }) => {
  requirePerm(account.id, params.pid, "MEMBERSHIP_MANAGE");
  const inv = db.invitations.find((i) => i.id === params.iid && i.providerId === params.pid);
  if (!inv) throw notFound("invitation");
  if (inv.status !== "PENDING") throw new ApiErr(409, "INVITATION_NOT_PENDING", "This invitation has already been answered or has lapsed.");
  inv.status = "WITHDRAWN";
  inv.respondedAt = now();
  return ok(invitationView(inv));
});
route("GET", "/api/v1/staff/providers/{pid}/customers/{mid}", ({ account, params }) => {
  requirePerm(account.id, params.pid, "MEMBERSHIP_VIEW");
  const m = db.memberships.find((x) => x.id === params.mid && x.providerId === params.pid);
  if (!m) throw notFound("membership");
  return ok(customerView(m));
});
route("POST", "/api/v1/staff/providers/{pid}/customers/{mid}/deactivate", ({ account, params }) => {
  const { provider } = requirePerm(account.id, params.pid, "MEMBERSHIP_MANAGE");
  writable(provider);
  const m = db.memberships.find((x) => x.id === params.mid && x.providerId === params.pid);
  if (!m) throw notFound("membership");
  if (m.status === "INACTIVE") throw new ApiErr(409, "MEMBERSHIP_ALREADY_INACTIVE", "This customer was already removed.");
  if (db.subs.some((s) => s.membershipId === m.id && ["RUNNING", "UPCOMING"].includes(phaseOf(s)))) throw new ApiErr(409, "MEMBERSHIP_HAS_ACTIVE_SUBSCRIPTION", "This customer still has an active subscription. Cancel it first.");
  m.status = "INACTIVE";
  m.leftAt = now();
  m.leftBy = account.id;
  return ok(customerView(m));
});

/* subscriptions: staff */
const findSub = (pid, id) => {
  const s = db.subs.find((x) => x.id === id && x.providerId === pid);
  if (!s) throw notFound("subscription");
  return s;
};
route("GET", "/api/v1/staff/providers/{pid}/subscriptions", ({ account, params, query }) => {
  requirePerm(account.id, params.pid, "SUBSCRIPTION_VIEW");
  return ok(db.subs.filter((s) => s.providerId === params.pid && (!query.membershipId || s.membershipId === query.membershipId) && (!query.status || s.status === query.status)).map(subView));
});
function sell(account, provider, membershipId, planId, startDate, renewedFrom) {
  const m = db.memberships.find((x) => x.id === membershipId && x.providerId === provider.id);
  if (!m) throw notFound("membership");
  if (m.status !== "ACTIVE") throw new ApiErr(409, "MEMBERSHIP_NOT_ACTIVE", "This customer is no longer active.");
  const pl = db.plans.find((x) => x.id === planId && x.providerId === provider.id);
  if (!pl) throw notFound("plan");
  if (pl.status !== "ACTIVE") throw new ApiErr(409, "PLAN_NOT_ACTIVE", "This plan is not active.");
  const start = startDate ?? today();
  if (start < add(today(), -1)) throw new ApiErr(400, "SUBSCRIPTION_PERIOD_IN_PAST", "The start date cannot be in the past.");
  const days = pl.durationDays;
  const pol = pl.policy;
  const base = days ? add(start, days - 1) : pol.maxCalendarWindowDays ? add(start, pol.maxCalendarWindowDays - 1) : null;
  if (db.subs.some((s) => s.membershipId === m.id && s.status === "ACTIVE" && s.id !== renewedFrom && phaseOf(s) !== "ENDED" && (base ?? "9999") >= s.startDate && start <= (s.effectiveExpiryDate ?? "9999"))) throw new ApiErr(409, "SUBSCRIPTION_OVERLAP", "This customer already has a subscription during those dates.");
  if (provider.maxActiveSubscriptions && db.subs.filter((s) => s.providerId === provider.id && !isStalePending(s) && ["RUNNING", "UPCOMING"].includes(phaseOf(s))).length >= provider.maxActiveSubscriptions) throw new ApiErr(409, "PROVIDER_AT_CAPACITY", "This provider is at its subscription limit.");
  const s = {
    id: uid(), providerId: provider.id, membershipId: m.id, planId: pl.id, consumptionType: pl.consumptionType, status: "ACTIVE", startDate: start, baseExpiryDate: base, effectiveExpiryDate: base,
    maximumExpiryDate: pol.maxCalendarWindowDays ? add(start, pol.maxCalendarWindowDays - 1) : null, createdBy: account.id, createdAt: now(), renewedFromSubscriptionId: renewedFrom ?? null,
    terms: { planName: pl.name, price: pl.price, currency: "INR", purchasedDays: days, purchasedMeals: pl.mealQuantity, policyVersion: pol.version, extensionAllowed: pol.extensionAllowed, minConsecutiveAbsenceDays: pol.minConsecutiveAbsenceDays, sameDayAbsenceAllowed: pol.sameDayAbsenceAllowed, absenceCutoffTime: pol.absenceCutoffTime, maxCalendarWindowDays: pol.maxCalendarWindowDays, capturedAt: now() },
  };
  db.subs.push(s);
  return s;
}
route("POST", "/api/v1/staff/providers/{pid}/subscriptions", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "SUBSCRIPTION_MANAGE");
  writable(provider);
  if (!body?.membershipId || !body?.planId) throw bad("VALIDATION_ERROR", "Request failed validation.");
  return ok(subView(sell(account, provider, body.membershipId, body.planId, body.startDate)), 201);
});
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}", ({ account, params }) => {
  requirePerm(account.id, params.pid, "SUBSCRIPTION_VIEW");
  return ok(subView(findSub(params.pid, params.id)));
});
route("POST", "/api/v1/staff/providers/{pid}/subscriptions/{id}/cancel", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "SUBSCRIPTION_MANAGE");
  writable(provider);
  const s = findSub(params.pid, params.id);
  if (!subView(s).cancellable) throw new ApiErr(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription can no longer be cancelled.");
  s.status = "CANCELLED";
  s.cancelledAt = now();
  s.cancelledBy = account.id;
  s.cancellationReason = body?.reason ?? null;
  return ok(subView(s));
});
function pendingSub(account, pid, id) {
  const s = findSub(pid, id);
  if (personOf(account.id)?.id === db.memberships.find((m) => m.id === s.membershipId)?.personId) throw new ApiErr(403, "OWN_SUBSCRIPTION", "You cannot verify your own subscription. Ask another member of staff.");
  if (s.verification?.status !== "PENDING") throw new ApiErr(409, "SUBSCRIPTION_NOT_PENDING", "This subscription has already been verified or rejected.");
  return s;
}
route("POST", "/api/v1/staff/providers/{pid}/subscriptions/{id}/verify", ({ account, params }) => {
  const { provider } = requirePerm(account.id, params.pid, "SUBSCRIPTION_MANAGE");
  writable(provider);
  const s = pendingSub(account, params.pid, params.id);
  s.verification = { ...s.verification, status: "VERIFIED", decidedAt: now(), rejectionReason: null };
  return ok(subView(s));
});
route("POST", "/api/v1/staff/providers/{pid}/subscriptions/{id}/reject", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "SUBSCRIPTION_MANAGE");
  writable(provider);
  const reason = body?.reason?.trim();
  if (!reason) throw bad("REJECTION_REASON_REQUIRED", "Say why the subscription is rejected - the customer will see it.");
  const s = pendingSub(account, params.pid, params.id);
  s.verification = { ...s.verification, status: "REJECTED", decidedAt: now(), rejectionReason: reason };
  s.status = "CANCELLED";
  s.cancelledAt = now();
  s.cancelledBy = account.id;
  s.cancellationReason = "Rejected: " + reason;
  return ok(subView(s));
});
route("POST", "/api/v1/staff/providers/{pid}/subscriptions/{id}/renew", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "SUBSCRIPTION_MANAGE");
  writable(provider);
  const s = findSub(params.pid, params.id);
  const v = subView(s);
  if (v.renewed) throw new ApiErr(409, "SUBSCRIPTION_ALREADY_RENEWED", "This subscription was already renewed.");
  if (!v.renewable) throw new ApiErr(409, "SUBSCRIPTION_NOT_RENEWABLE", "Only an ended subscription can be renewed.");
  // Real backend: a renewal starts today unless a date is given (which must be after the old end and not in the past).
  const start = body?.startDate ?? today();
  if (s.effectiveExpiryDate && start <= s.effectiveExpiryDate) throw bad("START_DATE_INVALID", `A renewal must start after the previous subscription ended (${s.effectiveExpiryDate}).`);
  return ok(subView(sell(account, provider, s.membershipId, body?.planId ?? s.planId, start, s.id)), 201);
});

/* subscriptions: customer */
const mySub = (account, id) => {
  const p = personOf(account.id);
  const s = db.subs.find((x) => x.id === id && db.memberships.find((m) => m.id === x.membershipId)?.personId === p?.id);
  if (!s) throw notFound("subscription");
  return s;
};
route("GET", "/api/v1/subscriptions", ({ account }) => {
  const p = personOf(account.id);
  const mine = db.subs.filter((s) => db.memberships.find((m) => m.id === s.membershipId)?.personId === p?.id);
  return ok(mine.sort((a, b) => b.startDate.localeCompare(a.startDate)).map(mySubView));
});
route("GET", "/api/v1/subscriptions/{id}", ({ account, params }) => ok(mySubView(mySub(account, params.id))));

/* attendance: staff */
route("GET", "/api/v1/staff/providers/{pid}/attendance", ({ account, params, query }) => {
  requirePerm(account.id, params.pid, "ATTENDANCE_VIEW");
  const date = query.date ?? today();
  const rows = db.subs
    .filter((s) => s.providerId === params.pid && s.status !== "CANCELLED" && s.startDate <= date && (!s.effectiveExpiryDate || date <= s.effectiveExpiryDate))
    .map((s) => {
      const v = subView(s);
      const d = dayView(s, date);
      return { subscriptionId: s.id, membershipId: s.membershipId, personId: v.personId, customerName: v.customerName, planName: s.terms.planName, startDate: s.startDate, effectiveExpiryDate: s.effectiveExpiryDate, status: d.status, source: d.source, assumed: d.assumed, absenceId: d.absenceId, reason: d.reason };
    });
  return ok({ date, expected: rows.length, present: rows.filter((r) => r.status === "PRESENT").length, absent: rows.filter((r) => r.status === "ABSENT").length, rows });
});
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}/attendance", ({ account, params, query }) => {
  requirePerm(account.id, params.pid, "ATTENDANCE_VIEW");
  const s = findSub(params.pid, params.id);
  const from = query.from ?? s.startDate;
  const to = query.to ?? s.effectiveExpiryDate ?? today();
  if (to < from) throw bad("DATE_RANGE_INVALID", "The end date is before the start date.");
  const out = [];
  for (let d = from < s.startDate ? s.startDate : from; d <= to && (!s.effectiveExpiryDate || d <= s.effectiveExpiryDate); d = add(d, 1)) out.push(dayView(s, d));
  return ok(out);
});
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}/attendance/{date}/history", ({ account, params }) => {
  requirePerm(account.id, params.pid, "ATTENDANCE_VIEW");
  const s = findSub(params.pid, params.id);
  return ok(db.records.filter((r) => r.subscriptionId === s.id && r.date === params.date).sort((a, b) => b.recordedAt.localeCompare(a.recordedAt)).map(({ subscriptionId, date, ...rest }) => rest));
});
route("PUT", "/api/v1/staff/providers/{pid}/subscriptions/{id}/attendance/{date}", ({ account, params, body }) => {
  const { provider } = requirePerm(account.id, params.pid, "ATTENDANCE_MANAGE");
  writable(provider);
  const s = findSub(params.pid, params.id);
  if (!["PRESENT", "ABSENT"].includes(body?.status) || !body?.reason?.trim()) throw bad("VALIDATION_ERROR", "Request failed validation.");
  const date = params.date;
  if (date < s.startDate || (s.effectiveExpiryDate && date > s.effectiveExpiryDate)) throw new ApiErr(400, "DATE_OUTSIDE_SUBSCRIPTION", "That date is outside the subscription.");
  if (s.status === "CANCELLED") throw new ApiErr(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is not active.");
  const open = db.absences.find((a) => a.subscriptionId === s.id && a.date === date && a.status === "DECLARED");
  if (body.status === "ABSENT") {
    let absenceId = open?.id ?? null;
    if (!open) {
      const a = { id: uid(), subscriptionId: s.id, date, status: "DECLARED", source: "OWNER_CORRECTION", reason: body.reason, declaredBy: account.id, declaredAt: now(), cancelledAt: null, cancelledBy: null, overriddenAt: null, overriddenBy: null, overrideReason: null };
      db.absences.push(a);
      absenceId = a.id;
    }
    setRecord(s, date, "ABSENT", "OWNER_CORRECTION", { absenceId, reason: body.reason, by: account.id });
  } else {
    if (open) Object.assign(open, { status: "OVERRIDDEN", overriddenAt: now(), overriddenBy: account.id, overrideReason: body.reason });
    setRecord(s, date, "PRESENT", "OWNER_CORRECTION", { absenceId: open?.id ?? null, reason: body.reason, by: account.id });
  }
  return ok(dayView(s, date));
});
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}/absences", ({ account, params }) => {
  requirePerm(account.id, params.pid, "ATTENDANCE_VIEW");
  const s = findSub(params.pid, params.id);
  return ok(db.absences.filter((a) => a.subscriptionId === s.id).sort((a, b) => a.date.localeCompare(b.date)));
});

/* attendance: customer */
route("GET", "/api/v1/subscriptions/{id}/attendance", ({ account, params, query }) => {
  const s = mySub(account, params.id);
  const from = query.from ?? s.startDate;
  const to = query.to ?? s.effectiveExpiryDate ?? today();
  if (to < from) throw bad("DATE_RANGE_INVALID", "The end date is before the start date.");
  const out = [];
  for (let d = from < s.startDate ? s.startDate : from; d <= to && (!s.effectiveExpiryDate || d <= s.effectiveExpiryDate); d = add(d, 1)) {
    const v = dayView(s, d);
    const lock = lockedReason(s, d);
    out.push({ date: d, status: v.status, assumed: v.assumed, absenceId: v.absenceId, reason: v.reason, providerSet: v.source === "OWNER_CORRECTION", changeable: lock === null, lockedReason: lock });
  }
  return ok(out);
});
route("GET", "/api/v1/subscriptions/{id}/absence", ({ account, params }) => ok(db.absences.filter((a) => a.subscriptionId === mySub(account, params.id).id).sort((a, b) => a.date.localeCompare(b.date)).map(myAbsenceView)));
route("POST", "/api/v1/subscriptions/{id}/absence", ({ account, params, body }) => {
  const s = mySub(account, params.id);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(body?.fromDate ?? "")) throw bad("VALIDATION_ERROR", "Request failed validation.");
  const r = declare(s, account.id, body.fromDate, body.toDate, body.reason);
  return ok({ absences: r.absences.map(myAbsenceView), anyCreated: r.anyCreated }, r.anyCreated ? 201 : 200);
});
route("POST", "/api/v1/subscriptions/{id}/absence/{aid}/cancel", ({ account, params }) => {
  const s = mySub(account, params.id);
  const a = db.absences.find((x) => x.id === params.aid && x.subscriptionId === s.id);
  if (!a) throw notFound("absence");
  if (a.status !== "DECLARED") throw new ApiErr(409, "ABSENCE_NOT_ACTIVE", "This absence is no longer active.");
  const lock = lockedReason(s, a.date);
  if (lock === "PAST") throw new ApiErr(409, "ABSENCE_DATE_IN_PAST", "You cannot change a day that has already passed.");
  if (lock === "PROVIDER_CORRECTION") throw new ApiErr(409, "ATTENDANCE_LOCKED_BY_PROVIDER", "This day was set by the provider.");
  a.status = "CANCELLED";
  a.cancelledAt = now();
  a.cancelledBy = account.id;
  clearRecord(s, a.date);
  return ok(myAbsenceView(a));
});

/* extension */
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}/extension", ({ account, params }) => {
  requirePerm(account.id, params.pid, "EXTENSION_VIEW");
  return ok(extensionStatus(findSub(params.pid, params.id)));
});
route("GET", "/api/v1/staff/providers/{pid}/subscriptions/{id}/extension/events", ({ account, params }) => {
  requirePerm(account.id, params.pid, "EXTENSION_VIEW");
  return ok(db.events.filter((e) => e.subscriptionId === findSub(params.pid, params.id).id).map(eventView));
});
route("POST", "/api/v1/staff/providers/{pid}/subscriptions/{id}/extension/apply", ({ account, params }) => {
  const { provider } = requirePerm(account.id, params.pid, "EXTENSION_MANAGE");
  writable(provider);
  const s = findSub(params.pid, params.id);
  const st = extensionStatus(s);
  if (st.blockedReason === "NOT_SUPPORTED_FOR_MEAL") throw new ApiErr(409, "EXTENSION_NOT_SUPPORTED", "Meal subscriptions are not extended by absence.");
  if (st.blockedReason === "NOT_ALLOWED_BY_POLICY") throw new ApiErr(409, "EXTENSION_NOT_ALLOWED", "This subscription was sold without extension for absence.");
  if (st.blockedReason) throw new ApiErr(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is not active.");
  if (!st.canApply) return ok({ applied: false, event: null, status: st });
  const ev = { id: uid(), subscriptionId: s.id, sequenceNo: db.events.filter((e) => e.subscriptionId === s.id).length + 1, kind: "EXTENSION_APPLIED", triggerSource: "STAFF", createdBy: account.id, evaluatedOn: today(), eligibleAbsenceDays: st.eligibleAbsenceDays, requestedExtensionDays: st.eligibleAbsenceDays - st.appliedExtensionDays, appliedExtensionDays: st.pendingExtensionDays, overAppliedDays: 0, previousExpiryDate: s.effectiveExpiryDate, newExpiryDate: st.projectedExpiryDate, maximumExpiryDate: s.maximumExpiryDate, capped: st.capped, createdAt: now() };
  db.events.push(ev);
  s.effectiveExpiryDate = st.projectedExpiryDate;
  return ok({ applied: true, event: ev, status: extensionStatus(s) });
});
route("GET", "/api/v1/subscriptions/{id}/extension", ({ account, params }) => ok(extensionStatus(mySub(account, params.id))));
route("GET", "/api/v1/subscriptions/{id}/extension/events", ({ account, params }) => ok(db.events.filter((e) => e.subscriptionId === mySub(account, params.id).id).map(myEventView)));

/* ------------------------------------------------------------------ server */

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const text = Buffer.concat(chunks).toString("utf8");
      if (!text) return resolve(undefined);
      try {
        resolve(JSON.parse(text));
      } catch {
        reject(bad("VALIDATION_ERROR", "Request body is malformed or contains an invalid value."));
      }
    });
    req.on("error", reject);
  });
}

function parseCookies(header = "") {
  return Object.fromEntries(header.split(/;\s*/).filter(Boolean).map((c) => { const i = c.indexOf("="); return [c.slice(0, i), c.slice(i + 1)]; }));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const send = (status, body, headers = {}) => {
    res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers });
    res.end(body === undefined ? undefined : JSON.stringify(body));
  };
  try {
    const route_ = routes.find((r) => r.method === req.method && r.re.test(url.pathname));
    if (!route_) {
      if (routes.some((r) => r.re.test(url.pathname))) return send(405, { code: "METHOD_NOT_ALLOWED", message: "This method is not supported for this resource." });
      return send(404, { code: "NOT_FOUND", message: "Resource not found." });
    }
    const m = url.pathname.match(route_.re);
    const params = Object.fromEntries(route_.keys.map((k, i) => [k, m[i + 1]]));
    const body = ["POST", "PUT", "PATCH", "DELETE"].includes(req.method) ? await readBody(req) : undefined;
    const ctx = { params, query: Object.fromEntries(url.searchParams), body, cookies: parseCookies(req.headers.cookie), cookieMode: req.headers["x-refresh-mode"] === "cookie" };
    if (route_.auth) {
      const token = (req.headers.authorization ?? "").replace(/^Bearer\s+/i, "");
      const entry = db.accessTokens.get(token);
      if (!entry || entry.exp < Date.now()) throw new ApiErr(401, "UNAUTHENTICATED", "Authentication is required.");
      ctx.account = accountById(entry.accountId);
      const path = url.pathname;
      const portal = path.startsWith("/api/v1/staff/") ? "staff" : /^\/api\/v1\/(subscriptions|me\/person|me\/memberships)/.test(path) ? "customer" : null;
      if (portal && !hasPortal(ctx.account, portal)) throw new ApiErr(403, "PORTAL_ACCESS_DENIED", `This account is not registered for the ${portal} portal.`);
    }
    const out = route_.handler(ctx);
    const headers = out.cookie ? { "Set-Cookie": out.cookie } : {};
    send(out.status, out.body, headers);
  } catch (err) {
    if (err instanceof ApiErr) return send(err.status, { code: err.code, message: err.message });
    console.error(err);
    send(500, { code: "INTERNAL_ERROR", message: "Something went wrong." });
  }
});

seed();
seedPastAbsences();
server.listen(PORT, () => {
  console.log(`FirstFood mock API on http://localhost:${PORT}  (any phone, code ${OTP}; access TTL ${ACCESS_TTL_MS / 1000}s)`);
});
