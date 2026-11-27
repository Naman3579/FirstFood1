-- F-06: capacity squatting through unpaid self-service subscriptions.
--
-- A customer's own purchase is stored ACTIVE with a PENDING verification and immediately counts toward the provider's
-- maxActiveSubscriptions. Nothing required payment, nothing ever expired a PENDING request, and a meal-based plan has no
-- end date, so a handful of throwaway accounts could fill a small provider and keep it full until staff noticed and
-- rejected every request by hand.
--
-- The fix is mostly code (capacity counts only confirmed or recently requested subscriptions; a per-account cap on
-- pending requests; an hourly job that closes requests nobody confirmed in time). The database changes here are the two
-- things that code needs:
--
--   1. A fourth verification outcome, EXPIRED: "nobody confirmed this request in time". It is a SYSTEM decision, so unlike
--      VERIFIED / REJECTED it has no deciding staff member and no reason text. Like REJECTED, it always goes together with
--      the subscription being CANCELLED (done by the job, in the same transaction), so its dates and capacity are freed.
--      Existing rows are untouched: the old three outcomes keep exactly their old shape.
--   2. An index for "how many requests is this account still waiting on", which the per-account cap asks on every
--      self-service purchase.
--
-- The guard trigger from V20 needs no change: it already allows exactly one transition out of PENDING and freezes the
-- request facts, which is what EXPIRED is.

alter table subscription_verification drop constraint subscription_verification_status_check;
alter table subscription_verification add constraint subscription_verification_status_check
    check (verification_status in ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED'));

alter table subscription_verification drop constraint subscription_verification_lifecycle_check;
alter table subscription_verification add constraint subscription_verification_lifecycle_check check (
    (verification_status = 'PENDING'
        and decided_by_account_id is null and decided_at is null and rejection_reason is null)
    or (verification_status = 'VERIFIED'
        and decided_by_account_id is not null and decided_at is not null and rejection_reason is null)
    or (verification_status = 'REJECTED'
        and decided_by_account_id is not null and decided_at is not null
        and rejection_reason is not null and length(btrim(rejection_reason)) > 0)
    or (verification_status = 'EXPIRED'
        and decided_by_account_id is null and decided_at is not null and rejection_reason is null));

-- Per-account cap on open requests (and a cheap "what is this customer waiting on").
create index idx_subscription_verification_pending_by_requester
    on subscription_verification (requested_by_account_id) where verification_status = 'PENDING';
