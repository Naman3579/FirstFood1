# Vacation and provider closures (V25)

Two ways a customer gets days back that are **not** absence extensions: their own *vacation* (return date unknown, ended
by the customer) and the provider's *closure* (the kitchen was shut; the owner decides whether customers are
compensated). Both are recorded as append-only `subscription_expiry_adjustment` rows; the effective expiry is always
`base + absence-extension days + adjustment days` and the database re-checks that at commit.

## The one date convention
- **Vacation:** start day INCLUSIVE, resume day EXCLUSIVE. 10 Nov -> resumed 20 Nov withheld 10 days.
- **Closure:** inclusive on both ends. 15..17 Nov = 3 days (at most 31 per closure).
- A date is **never paid twice**: a resumed vacation subtracts dates a closure already covered, and a closure subtracts
  dates inside vacations already resumed. Same total in either order (`PauseRulesTest`).

## Customer vacation (`/api/v1/subscriptions/{id}/vacation`)
- `GET` status, `POST` start (body optional, no start date = today, up to 60 days ahead), `POST /resume`.
- DAY subscriptions only; ACTIVE, confirmed by the provider, not renewed, provider not closed, no vacation already open.
- Resume is idempotent (row lock + `uq_adjustment_pause`). Resuming a vacation that has not begun just calls it off.
- While on vacation the subscription stays RUNNING even if its stored end date passes (`Subscription.isPausedOn`); the
  lapse job and the absence-extension scan skip it; the daily attendance sheet leaves it out; declaring an absence on a
  vacation day is refused (`DATE_ON_VACATION`) so a day is not extended twice. Remaining days are frozen at the value
  they had when the vacation began.
- A vacation must start on or before the expiry, and resuming on day R adds the withheld days to it, so the new expiry is never
  before R: a subscription cannot lapse mid-vacation.

## Provider closure (`/api/v1/staff/providers/{id}/closures`)
- `GET` list, `GET /preview?from&to` (dry run, writes nothing), `POST` declare, `POST /{id}/cancel`.
- Needs `SUBSCRIPTION_MANAGE` to declare/cancel, `SUBSCRIPTION_VIEW` to read. **The extend decision has no default**
  (`CLOSURE_DECISION_REQUIRED`); repeating a declaration with the other choice is `CLOSURE_DECISION_CONFLICT`.
- Declare is one transaction: closure row + one adjustment per eligible subscription. Idempotent on (provider, dates).
- Eligible: DAY, ACTIVE, confirmed, not renewed, entitled on at least one closure day. A subscription that starts after the
  closure gets nothing; one that ends during it gets the days up to its expiry.
- A closure that extended subscriptions can never be cancelled; one that extended nothing can.
- Backdate up to 14 days, announce up to 180 days ahead. Lock order: provider row, then subscription rows by id.

## Interaction with the absence-extension engine
`SubscriptionRef.baseExpiryDate` and `maximumExpiryDate` are shifted by the adjustment days (the same shift V25's guard
applies), so a closure or vacation neither counts as extension already used nor eats the headroom under the plan's
calendar-window cap. `SubscriptionView.adjustmentDays` shows the total.

## Decisions to confirm
1. Closure eligibility ignores the plan's absence-extension setting (it is the owner's decision).
2. 60 days maximum schedule-ahead for a vacation, 31 days per closure, 14 days back-dating.
3. A vacation cannot start on an unconfirmed or already-renewed subscription.
4. No notification to customers is sent when a closure extends them (none exists in this codebase yet).

## Not covered by automated tests
The credit of a vacation that has lasted several days needs a clock that can move; `PauseRulesTest` covers the arithmetic and
`VacationAndClosureIntegrationTest` covers the API flows, but there is no end-to-end test of "resume after N days".
