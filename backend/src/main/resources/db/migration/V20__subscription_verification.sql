-- Staff verification of NEW subscriptions, the same idea as payment verification (V17).
--
-- A subscription a customer buys or renews for themselves is only a request until the provider's staff confirm it:
-- it starts PENDING. A subscription staff sell themselves is VERIFIED straight away (the seller is the verifier).
-- Rejecting a pending subscription also cancels it (it never becomes entitlement), with the reason the customer sees.
--
-- Kept in its own 1:1 table so the subscription row and its immutable snapshot are untouched (V10 / V12 triggers).
-- Existing subscriptions are backfilled as VERIFIED: they were all accepted before this check existed.

create table subscription_verification (
    subscription_id uuid primary key references subscription (id),
    provider_id uuid not null references food_provider (id),
    verification_status varchar(20) not null default 'PENDING',
    -- Who asked for it: the customer's account for a self-service purchase, the staff member otherwise.
    requested_by_account_id uuid not null references user_account (id),
    requested_at timestamptz not null default now(),
    decided_by_account_id uuid references user_account (id),
    decided_at timestamptz,
    rejection_reason varchar(500),

    constraint subscription_verification_status_check
        check (verification_status in ('PENDING', 'VERIFIED', 'REJECTED')),
    constraint subscription_verification_lifecycle_check check (
        (verification_status = 'PENDING'
            and decided_by_account_id is null and decided_at is null and rejection_reason is null)
        or (verification_status = 'VERIFIED'
            and decided_by_account_id is not null and decided_at is not null and rejection_reason is null)
        or (verification_status = 'REJECTED'
            and decided_by_account_id is not null and decided_at is not null
            and rejection_reason is not null and length(btrim(rejection_reason)) > 0))
);

-- Staff work queue: "which new subscriptions are waiting for me".
create index idx_subscription_verification_pending
    on subscription_verification (provider_id, requested_at) where verification_status = 'PENDING';

insert into subscription_verification (subscription_id, provider_id, verification_status, requested_by_account_id,
                                       requested_at, decided_by_account_id, decided_at)
select s.id, s.provider_id, 'VERIFIED', s.created_by, s.created_at, s.created_by, s.created_at
from subscription s;

-- After insert only PENDING -> VERIFIED | REJECTED, once; the request facts never change; never deleted.
create function subscription_verification_guard() returns trigger as $$
begin
    if new.subscription_id is distinct from old.subscription_id
        or new.provider_id is distinct from old.provider_id
        or new.requested_by_account_id is distinct from old.requested_by_account_id
        or new.requested_at is distinct from old.requested_at then
        raise exception 'subscription_verification: the request is immutable' using errcode = 'check_violation';
    end if;
    if old.verification_status <> 'PENDING' then
        raise exception 'subscription_verification is already % and can no longer change', old.verification_status
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger subscription_verification_no_identity_change
    before update on subscription_verification
    for each row execute function subscription_verification_guard();

create function subscription_verification_reject_delete() returns trigger as $$
begin
    raise exception 'subscription_verification rows are never deleted' using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_verification_no_delete
    before delete on subscription_verification
    for each row execute function subscription_verification_reject_delete();
