-- F-08: owner-level actions need consent and a fresh proof of identity.
--
-- 1. OTP purpose OWNER_ACTION: a code sent to the owner's own phone, required to transfer ownership or close a provider,
--    so a stolen 15-minute access token (or an unattended phone) is no longer enough.
-- 2. ownership_offer: a transfer is no longer instant. The owner OFFERS ownership to a phone number and the target must
--    accept while signed in. Keyed by PHONE, like membership_invitation (V21), so an offer to an unregistered number looks
--    identical to one to a registered number: no account-existence oracle. Nothing changes hands until acceptance. Rows
--    are never deleted.

alter table otp_verification drop constraint otp_verification_purpose_check;
alter table otp_verification add constraint otp_verification_purpose_check
    check (purpose in ('LOGIN', 'PHONE_CHANGE', 'EMAIL_CHANGE', 'OWNER_ACTION'));

create table ownership_offer (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    -- The OWNER who made the offer.
    offered_by uuid not null references user_account (id),
    -- Canonical form: always with a leading '+'.
    target_phone varchar(20) not null,
    status varchar(20) not null default 'PENDING',
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    responded_at timestamptz,
    -- Who accepted/declined, or the owner who withdrew. NULL for EXPIRED (set by the system).
    responded_by uuid references user_account (id),
    updated_at timestamptz not null default now(),

    constraint ownership_offer_status_check check (
        status in ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED')),
    constraint ownership_offer_phone_check check (target_phone ~ '^\+[1-9][0-9]{9,14}$'),
    constraint ownership_offer_expiry_check check (expires_at > created_at),
    constraint ownership_offer_lifecycle_check check (
        (status = 'PENDING' and responded_at is null and responded_by is null)
        or (status = 'EXPIRED' and responded_at is not null and responded_by is null)
        or (status in ('ACCEPTED', 'DECLINED', 'WITHDRAWN') and responded_at is not null and responded_by is not null))
);

-- One open offer per provider: the owner withdraws the old one before making another.
create unique index uq_ownership_offer_one_pending on ownership_offer (provider_id) where status = 'PENDING';
create index idx_ownership_offer_target on ownership_offer (target_phone, status);
create index idx_ownership_offer_provider on ownership_offer (provider_id, created_at desc);

comment on table ownership_offer is
    'Owner-to-phone offer to take over a provider. Ownership moves only when the target accepts (provider_role_audit records the move).';
