-- V25: customer vacation (pause with an unknown return date) and provider-declared closures.
--
-- THREE NEW FACTS, ONE EXISTING INVARIANT WIDENED.
--
--   subscription_pause              a customer's vacation: OPEN until they resume (or it is cancelled before it began).
--   provider_closure                a provider's closure dates and the owner's explicit extend / don't-extend decision.
--   subscription_expiry_adjustment  append-only audit of every expiry change caused by a RESUMED vacation
--                                   (VACATION_RESUME) or an applied closure (PROVIDER_CLOSURE). Distinct from
--                                   extension_event, which stays "what absence earned" (V12/V13).
--
-- THE INVARIANT (V12, widened here). For a DAY subscription
--     effective_expiry_date = base_expiry_date + sum(extension_event.applied_extension_days)
--                                              + sum(subscription_expiry_adjustment.days)
-- checked at COMMIT in every direction, so the expiry still cannot move without an event that explains it.
--
-- ABSENCE CAP. The absence engine's "maximum allowed expiry" (start + window - 1) answers "how far may ABSENCE push a
-- subscription". A vacation or a provider closure is not absence: it gives back days the customer could not eat. So the
-- cap the absence engine sees is shifted by the adjustment days, and a closure never uses up the headroom an absent
-- customer has earned. extension_guard_insert is redefined below to use the shifted cap.
--
-- DAY SUBSCRIPTIONS ONLY. A MEAL subscription keeps its meal quantity whatever the provider does and its calendar window
-- is NOT paused or moved (decision recorded in docs/VACATION_AND_CLOSURES.md).
--
-- DATE CONVENTION (inclusive start, exclusive resume): a pause that starts on S and resumes on R withheld the days
-- S .. R-1, i.e. R - S days. Start 10 Nov, resume 20 Nov = 10 days. A closure start..end is inclusive on both ends.

-- ------------------------------------------------------------------------------------------------
-- provider_closure
-- ------------------------------------------------------------------------------------------------
create table provider_closure (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    start_date date not null,
    end_date date not null,
    reason varchar(200),
    -- The owner's explicit decision. false = recorded for information only: no expiry is ever touched.
    extend_subscriptions boolean not null,
    status varchar(10) not null default 'DECLARED',
    created_by uuid not null references user_account (id),
    created_at timestamptz not null default now(),
    cancelled_by uuid references user_account (id),
    cancelled_at timestamptz,

    constraint closure_dates_check check (end_date >= start_date and end_date - start_date <= 30),
    constraint closure_status_check check (status in ('DECLARED', 'CANCELLED')),
    constraint closure_reason_not_blank check (reason is null or length(btrim(reason)) > 0),
    constraint closure_lifecycle_check check (
        (status = 'DECLARED' and cancelled_by is null and cancelled_at is null)
        or (status = 'CANCELLED' and cancelled_by is not null and cancelled_at is not null)),
    -- Two live closures of one provider never share a day: the same date can never be compensated twice.
    constraint closure_no_overlap
        exclude using gist (provider_id with =, daterange(start_date, end_date, '[]') with &&)
        where (status = 'DECLARED')
);

create index idx_closure_provider_start on provider_closure (provider_id, start_date desc);

-- ------------------------------------------------------------------------------------------------
-- subscription_pause
-- ------------------------------------------------------------------------------------------------
alter table subscription add column paused_since date;

create table subscription_pause (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    provider_id uuid not null,
    consumption_type varchar(10) not null default 'DAY',
    -- First day the customer is NOT served. May be in the future (a scheduled vacation).
    start_date date not null,
    status varchar(10) not null default 'OPEN',
    -- First day service resumes. Set on RESUMED only; resumed_on = start_date means the pause never withheld a day.
    resumed_on date,
    paused_days integer,
    -- Days given back: paused_days minus the days a provider closure already compensated.
    credited_days integer,
    created_by uuid not null references user_account (id),
    closed_by uuid references user_account (id),
    closed_at timestamptz,
    created_at timestamptz not null default now(),

    constraint pause_subscription_fk
        foreign key (subscription_id, provider_id, consumption_type)
        references subscription (id, provider_id, consumption_type),
    constraint pause_day_only_check check (consumption_type = 'DAY'),
    constraint pause_status_check check (status in ('OPEN', 'RESUMED', 'CANCELLED')),
    constraint pause_lifecycle_check check (
        (status = 'OPEN'
            and resumed_on is null and paused_days is null and credited_days is null
            and closed_at is null and closed_by is null)
        or (status = 'RESUMED'
            and resumed_on is not null and resumed_on >= start_date
            and paused_days is not null and paused_days = resumed_on - start_date
            and credited_days is not null and credited_days between 0 and paused_days
            and closed_at is not null and closed_by is not null)
        or (status = 'CANCELLED'
            and resumed_on is null and paused_days is null and credited_days is null
            and closed_at is not null and closed_by is not null)),
    -- One open vacation per subscription ...
    constraint pause_no_overlap
        exclude using gist (subscription_id with =, daterange(start_date, resumed_on, '[)') with &&)
        where (status <> 'CANCELLED')
);
-- ... (the exclusion above also stops a second OPEN one: both have an unbounded range), and a plain lookup index.
create unique index uq_pause_one_open on subscription_pause (subscription_id) where status = 'OPEN';
create index idx_pause_subscription on subscription_pause (subscription_id, start_date);

create function subscription_pause_guard_update() returns trigger as $$
begin
    if new.subscription_id is distinct from old.subscription_id
        or new.provider_id is distinct from old.provider_id
        or new.start_date is distinct from old.start_date
        or new.created_by is distinct from old.created_by
        or new.created_at is distinct from old.created_at then
        raise exception 'a vacation''s subscription, start date and creator never change'
            using errcode = 'check_violation';
    end if;
    if old.status <> 'OPEN' then
        raise exception 'a vacation that is % can no longer change', old.status
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger subscription_pause_no_identity_change
    before update on subscription_pause
    for each row execute function subscription_pause_guard_update();

create function subscription_pause_reject_delete() returns trigger as $$
begin
    raise exception 'vacation rows are never deleted' using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_pause_no_delete
    before delete on subscription_pause
    for each row execute function subscription_pause_reject_delete();

-- subscription.paused_since mirrors the OPEN pause's start_date (it lets "is it paused?" be answered without a join).
-- Deferred to COMMIT because the two rows are written one after the other.
create function assert_pause_marker_consistent(subscription_uuid uuid) returns void as $$
declare
    sub subscription%rowtype;
    open_start date;
begin
    select * into sub from subscription where id = subscription_uuid;
    if not found then
        return;
    end if;
    select start_date into open_start from subscription_pause
    where subscription_id = subscription_uuid and status = 'OPEN';
    if sub.paused_since is distinct from open_start then
        raise exception 'subscription % says it is paused since % but its open vacation starts %',
            subscription_uuid, sub.paused_since, open_start
            using errcode = 'check_violation';
    end if;
end;
$$ language plpgsql;

create function subscription_pause_marker_from_subscription() returns trigger as $$
begin
    perform assert_pause_marker_consistent(new.id);
    return null;
end;
$$ language plpgsql;

create function subscription_pause_marker_from_pause() returns trigger as $$
begin
    perform assert_pause_marker_consistent(new.subscription_id);
    return null;
end;
$$ language plpgsql;

create constraint trigger subscription_pause_marker_matches_pause
    after insert or update on subscription
    deferrable initially deferred
    for each row execute function subscription_pause_marker_from_subscription();

create constraint trigger pause_marker_matches_subscription
    after insert or update on subscription_pause
    deferrable initially deferred
    for each row execute function subscription_pause_marker_from_pause();

-- ------------------------------------------------------------------------------------------------
-- subscription_expiry_adjustment (append-only)
-- ------------------------------------------------------------------------------------------------
create table subscription_expiry_adjustment (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    provider_id uuid not null,
    consumption_type varchar(10) not null default 'DAY',
    kind varchar(20) not null,
    pause_id uuid references subscription_pause (id),
    closure_id uuid references provider_closure (id),
    -- The calendar days this adjustment accounts for. PROVIDER_CLOSURE: the closure's dates that fell inside the
    -- subscription's entitlement (before removing days a vacation already compensated). VACATION_RESUME: S .. R-1.
    -- A vacation uses covered_* to avoid paying again for dates a closure already paid for, and vice versa.
    covered_from date not null,
    covered_to date not null,
    days integer not null,
    previous_expiry_date date not null,
    new_expiry_date date not null,
    created_by uuid not null references user_account (id),
    created_at timestamptz not null default now(),

    constraint adjustment_subscription_fk
        foreign key (subscription_id, provider_id, consumption_type)
        references subscription (id, provider_id, consumption_type),
    constraint adjustment_day_only_check check (consumption_type = 'DAY'),
    constraint adjustment_kind_check check (kind in ('VACATION_RESUME', 'PROVIDER_CLOSURE')),
    constraint adjustment_source_check check (
        (kind = 'VACATION_RESUME' and pause_id is not null and closure_id is null)
        or (kind = 'PROVIDER_CLOSURE' and closure_id is not null and pause_id is null)),
    constraint adjustment_days_check check (days >= 1),
    constraint adjustment_covered_check check (covered_to >= covered_from and days <= covered_to - covered_from + 1),
    constraint adjustment_dates_check check (new_expiry_date = previous_expiry_date + days)
);

-- Idempotency: one adjustment per vacation, one per (closure, subscription). A retry or a second job run cannot
-- apply the same thing twice, whatever the application did.
create unique index uq_adjustment_pause on subscription_expiry_adjustment (pause_id) where pause_id is not null;
create unique index uq_adjustment_closure_subscription
    on subscription_expiry_adjustment (closure_id, subscription_id) where closure_id is not null;
create index idx_adjustment_subscription on subscription_expiry_adjustment (subscription_id);
create index idx_adjustment_closure on subscription_expiry_adjustment (closure_id);

create function adjustment_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
begin
    select * into sub from subscription where id = new.subscription_id;
    if sub.status <> 'ACTIVE' then
        raise exception 'subscription % is %: its expiry can no longer be adjusted', new.subscription_id, sub.status
            using errcode = 'check_violation';
    end if;
    -- A renewal starts after the previous expiry; moving the expiry later would overlap it.
    if exists (select 1 from subscription r where r.renewed_from_subscription_id = new.subscription_id) then
        raise exception 'subscription % has been renewed: its expiry can no longer be adjusted', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if new.previous_expiry_date is distinct from sub.effective_expiry_date then
        raise exception 'adjustment starts from % but the subscription currently expires on %',
            new.previous_expiry_date, sub.effective_expiry_date
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger adjustment_guard_insert_trigger
    before insert on subscription_expiry_adjustment
    for each row execute function adjustment_guard_insert();

create function adjustment_reject_change() returns trigger as $$
begin
    raise exception 'subscription_expiry_adjustment rows are append-only: they are never updated or deleted'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger adjustment_append_only
    before update or delete on subscription_expiry_adjustment
    for each row execute function adjustment_reject_change();

-- A closure that has already extended subscriptions is never cancelled silently: history is not rewritten.
create function provider_closure_guard_update() returns trigger as $$
begin
    if new.provider_id is distinct from old.provider_id
        or new.start_date is distinct from old.start_date
        or new.end_date is distinct from old.end_date
        or new.extend_subscriptions is distinct from old.extend_subscriptions
        or new.created_by is distinct from old.created_by
        or new.created_at is distinct from old.created_at
        or new.reason is distinct from old.reason then
        raise exception 'a declared closure''s provider, dates, reason and decision never change'
            using errcode = 'check_violation';
    end if;
    if old.status <> 'DECLARED' then
        raise exception 'a closure that is % can no longer change', old.status using errcode = 'check_violation';
    end if;
    if new.status = 'CANCELLED'
        and exists (select 1 from subscription_expiry_adjustment where closure_id = old.id) then
        raise exception 'closure % has already extended subscriptions: it cannot be cancelled', old.id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger provider_closure_no_rewrite
    before update on provider_closure
    for each row execute function provider_closure_guard_update();

create function provider_closure_reject_delete() returns trigger as $$
begin
    raise exception 'closure rows are never deleted' using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger provider_closure_no_delete
    before delete on provider_closure
    for each row execute function provider_closure_reject_delete();

-- ------------------------------------------------------------------------------------------------
-- The widened expiry invariant (replaces V12's function; the triggers that call it are unchanged).
-- ------------------------------------------------------------------------------------------------
create or replace function assert_expiry_matches_extensions(subscription_uuid uuid) returns void as $$
declare
    sub subscription%rowtype;
    applied bigint;
    adjusted bigint;
begin
    select * into sub from subscription where id = subscription_uuid;
    if not found or sub.consumption_type <> 'DAY' then
        return;
    end if;
    select coalesce(sum(applied_extension_days), 0) into applied
    from extension_event where subscription_id = subscription_uuid;
    select coalesce(sum(days), 0) into adjusted
    from subscription_expiry_adjustment where subscription_id = subscription_uuid;
    if sub.effective_expiry_date is distinct from sub.base_expiry_date + (applied + adjusted)::integer then
        raise exception 'subscription % expires on % but its base expiry % plus % extension day(s) and % adjustment day(s) is %',
            subscription_uuid, sub.effective_expiry_date, sub.base_expiry_date, applied, adjusted,
            sub.base_expiry_date + (applied + adjusted)::integer
            using errcode = 'check_violation';
    end if;
end;
$$ language plpgsql;

create function adjustment_expiry_consistent() returns trigger as $$
begin
    perform assert_expiry_matches_extensions(new.subscription_id);
    return null;
end;
$$ language plpgsql;

create constraint trigger adjustment_expiry_matches_subscription
    after insert on subscription_expiry_adjustment
    deferrable initially deferred
    for each row execute function adjustment_expiry_consistent();

-- ------------------------------------------------------------------------------------------------
-- extension_guard_insert, V13 version, with the cap and the "already applied" arithmetic shifted by the adjustment
-- days (see "ABSENCE CAP" above). With no adjustments the function behaves exactly as before.
-- ------------------------------------------------------------------------------------------------
create or replace function extension_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
    snap subscription_term_snapshot%rowtype;
    last_sequence integer;
    adjusted integer;
    cap date;
begin
    select * into sub from subscription where id = new.subscription_id;
    select coalesce(sum(days), 0)::integer into adjusted
    from subscription_expiry_adjustment where subscription_id = new.subscription_id;
    -- The snapshot is read LAST: the "not found" test below must see the outcome of this select (as in V13).
    select * into snap from subscription_term_snapshot where subscription_id = new.subscription_id;
    cap := case when snap.max_calendar_window_days is null then null
                else sub.start_date + (snap.max_calendar_window_days - 1) + adjusted end;

    if sub.status <> 'ACTIVE' then
        raise exception 'subscription % is %: it can no longer be extended', new.subscription_id, sub.status
            using errcode = 'check_violation';
    end if;
    if not found or not snap.extension_allowed then
        raise exception 'subscription % was not sold with extension allowed', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if exists (select 1 from subscription r where r.renewed_from_subscription_id = new.subscription_id) then
        raise exception 'subscription % has been renewed: it can no longer be extended', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if new.previous_expiry_date is distinct from sub.effective_expiry_date then
        raise exception 'extension starts from % but the subscription currently expires on %',
            new.previous_expiry_date, sub.effective_expiry_date
            using errcode = 'check_violation';
    end if;
    select coalesce(max(sequence_no), 0) into last_sequence
    from extension_event where subscription_id = new.subscription_id;
    if new.sequence_no <> last_sequence + 1 then
        raise exception 'extension sequence % is not the next one (%)', new.sequence_no, last_sequence + 1
            using errcode = 'check_violation';
    end if;
    if cap is not null and new.new_expiry_date > cap then
        raise exception 'extension to % goes past the maximum allowed expiry %', new.new_expiry_date, cap
            using errcode = 'check_violation';
    end if;
    if new.maximum_expiry_date is distinct from cap then
        raise exception 'extension records a maximum expiry that does not match the subscription''s policy'
            using errcode = 'check_violation';
    end if;
    if new.event_kind = 'OVER_APPLIED'
        and new.already_applied_extension_days is distinct from
            (sub.effective_expiry_date - sub.base_expiry_date - adjusted) then
        raise exception 'over-applied report says % day(s) are applied but the subscription carries %',
            new.already_applied_extension_days, sub.effective_expiry_date - sub.base_expiry_date - adjusted
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

comment on table subscription_pause is
    'A customer''s vacation. OPEN until resumed (return date unknown) or cancelled before it began. Days withheld = resumed_on - start_date.';
comment on column subscription.paused_since is
    'start_date of the subscription''s OPEN vacation, null when not paused. Checked against subscription_pause at commit.';
comment on table provider_closure is
    'A provider''s closure dates and the owner''s explicit decision whether to extend DAY subscriptions. Never rewritten; cancellable only while it has extended nothing.';
comment on table subscription_expiry_adjustment is
    'Append-only audit of every expiry change from a resumed vacation or an applied provider closure. effective_expiry_date = base + extension events + adjustments (checked at commit).';
