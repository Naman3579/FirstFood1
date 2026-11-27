-- Phase 11: payments against a subscription.
--
-- A subscription can be paid for in one go, in instalments, before it starts or long after. So money is NOT a
-- column on the subscription: every payment the customer reports is its own row here, linked to the subscription,
-- and the subscription (and its immutable term snapshot) are not touched at all.
--
-- A row is a CLAIM until staff verify it. customer_payment_date / customer_payment_time are what the customer says;
-- submitted_at is what the server saw. Only VERIFIED rows count towards what has been paid; PENDING rows are only
-- counted against over-payment, REJECTED rows are ignored entirely (and free their reference for a corrected resubmit).
--
-- subscription_payment is never deleted. After insert only the verification outcome may be written, once.
-- subscription_payment_event is the append-only audit of who submitted / verified / rejected what, and when.

-- Lets a payment be tied to ITS subscription's provider by foreign key (same trick as V10), so
-- provider_id on a payment can never disagree with the subscription's.
alter table subscription
    add constraint uq_subscription_id_provider unique (id, provider_id);

create table subscription_payment (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    -- Denormalised on purpose (architecture.md §34): every provider-owned row carries its provider.
    provider_id uuid not null references food_provider (id),
    -- The account that submitted it. Always the subscription's owner; staff cannot submit on a customer's behalf.
    customer_account_id uuid not null references user_account (id),
    -- The name as it was when submitted, so later profile edits do not rewrite what staff were shown.
    subscriber_name_snapshot varchar(150) not null,
    -- UTR / UPI reference / receipt number as typed by the customer. Optional only for CASH and OTHER.
    transaction_reference varchar(150),
    amount numeric(12, 2) not null,
    -- Copied from the subscription's term snapshot at submit time.
    currency varchar(3) not null,
    payment_method varchar(20) not null,
    -- Customer's claim, in the business time zone. NOT verified facts.
    customer_payment_date date not null,
    customer_payment_time time not null,
    verification_status varchar(20) not null default 'PENDING',
    submitted_at timestamptz not null default now(),
    verified_by_account_id uuid references user_account (id),
    verified_at timestamptz,
    rejection_reason varchar(500),
    -- Client-generated key: a retried request returns the original payment instead of creating a second one.
    idempotency_key varchar(64),

    constraint subscription_payment_subscription_fk
        foreign key (subscription_id, provider_id) references subscription (id, provider_id),

    constraint subscription_payment_amount_positive check (amount > 0),
    constraint subscription_payment_currency_format check (currency ~ '^[A-Z]{3}$'),
    constraint subscription_payment_method_check check (payment_method in ('UPI', 'BANK_TRANSFER', 'CASH', 'OTHER')),
    constraint subscription_payment_status_check check (verification_status in ('PENDING', 'VERIFIED', 'REJECTED')),
    constraint subscription_payment_name_not_blank check (length(btrim(subscriber_name_snapshot)) > 0),
    constraint subscription_payment_reference_not_blank check (
        transaction_reference is null or length(btrim(transaction_reference)) > 0),
    -- Digital payments are meaningless without a reference to check.
    constraint subscription_payment_reference_required check (
        payment_method in ('CASH', 'OTHER') or transaction_reference is not null),
    -- Verification columns are present exactly for the state they describe.
    constraint subscription_payment_lifecycle_check check (
        (verification_status = 'PENDING'
            and verified_by_account_id is null and verified_at is null and rejection_reason is null)
        or (verification_status = 'VERIFIED'
            and verified_by_account_id is not null and verified_at is not null and rejection_reason is null)
        or (verification_status = 'REJECTED'
            and verified_by_account_id is not null and verified_at is not null
            and rejection_reason is not null and length(btrim(rejection_reason)) > 0))
);

create index idx_subscription_payment_subscription on subscription_payment (subscription_id, submitted_at desc);
-- Staff work queue: "what is waiting for me to verify".
create index idx_subscription_payment_provider_pending on subscription_payment (provider_id, submitted_at)
    where verification_status = 'PENDING';

-- Retry protection: one idempotency key per customer.
create unique index uq_subscription_payment_idempotency
    on subscription_payment (customer_account_id, idempotency_key) where idempotency_key is not null;

-- Duplicate references. A UPI/bank reference is unique across the whole system, so the same UTR cannot be
-- claimed twice (on one subscription or two). Cash / other receipt numbers are only unique within one provider's
-- receipt book. Case and surrounding spaces are ignored. A REJECTED payment releases its reference, so the
-- customer can resubmit it corrected.
create unique index uq_subscription_payment_digital_reference
    on subscription_payment (payment_method, lower(btrim(transaction_reference)))
    where payment_method in ('UPI', 'BANK_TRANSFER') and verification_status <> 'REJECTED';
create unique index uq_subscription_payment_receipt_reference
    on subscription_payment (provider_id, payment_method, lower(btrim(transaction_reference)))
    where payment_method in ('CASH', 'OTHER') and transaction_reference is not null
      and verification_status <> 'REJECTED';

-- What may change after insert: only PENDING -> VERIFIED | REJECTED, once, and only the verification columns.
create function subscription_payment_guard_update() returns trigger as $$
begin
    if new.id is distinct from old.id
        or new.subscription_id is distinct from old.subscription_id
        or new.provider_id is distinct from old.provider_id
        or new.customer_account_id is distinct from old.customer_account_id
        or new.subscriber_name_snapshot is distinct from old.subscriber_name_snapshot
        or new.transaction_reference is distinct from old.transaction_reference
        or new.amount is distinct from old.amount
        or new.currency is distinct from old.currency
        or new.payment_method is distinct from old.payment_method
        or new.customer_payment_date is distinct from old.customer_payment_date
        or new.customer_payment_time is distinct from old.customer_payment_time
        or new.submitted_at is distinct from old.submitted_at
        or new.idempotency_key is distinct from old.idempotency_key then
        raise exception 'subscription_payment: what the customer submitted is immutable'
            using errcode = 'check_violation';
    end if;
    if old.verification_status <> 'PENDING' then
        raise exception 'subscription_payment is already % and can no longer change', old.verification_status
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger subscription_payment_no_identity_change
    before update on subscription_payment
    for each row execute function subscription_payment_guard_update();

create function subscription_payment_reject_delete() returns trigger as $$
begin
    raise exception 'subscription_payment rows are never deleted'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_payment_no_delete
    before delete on subscription_payment
    for each row execute function subscription_payment_reject_delete();

-- Append-only audit.
create table subscription_payment_event (
    id uuid primary key default gen_random_uuid(),
    payment_id uuid not null references subscription_payment (id),
    provider_id uuid not null references food_provider (id),
    event_type varchar(20) not null,
    actor_account_id uuid not null references user_account (id),
    -- The rejection reason for REJECTED; null otherwise.
    detail varchar(500),
    occurred_at timestamptz not null default now(),
    constraint subscription_payment_event_type_check check (event_type in ('SUBMITTED', 'VERIFIED', 'REJECTED'))
);

create index idx_subscription_payment_event_payment on subscription_payment_event (payment_id, occurred_at);

create function subscription_payment_event_reject_change() returns trigger as $$
begin
    raise exception 'subscription_payment_event rows are append-only: they are never updated or deleted'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_payment_event_append_only
    before update or delete on subscription_payment_event
    for each row execute function subscription_payment_event_reject_change();
