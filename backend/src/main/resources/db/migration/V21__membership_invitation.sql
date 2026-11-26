-- F-03: enrolment becomes an INVITATION the customer must accept.
--
-- Before this, a staff member could type any registered phone number and instantly become that person's "provider":
-- they received the person's name, phone and saved delivery address, the person was never asked, and the answer
-- ("not registered" vs success) revealed whether a number has an account.
--
-- The invitation is keyed by PHONE, not by account, so an invitation to a number with no account is stored exactly like
-- one to a registered number: the provider cannot tell the difference (no account-existence oracle). A membership row
-- is created only when the invited person accepts. Rows are never deleted.

create table membership_invitation (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    -- Canonical form: always with a leading '+'. Accounts store phones with or without it, so matching canonicalises both.
    invited_phone varchar(20) not null,
    invited_by uuid not null references user_account (id),
    status varchar(20) not null default 'PENDING',
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    responded_at timestamptz,
    -- The customer who accepted/declined, or the staff member who withdrew.
    responded_by uuid references user_account (id),
    membership_id uuid references provider_membership (id),
    updated_at timestamptz not null default now(),

    constraint membership_invitation_status_check check (
        status in ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED')),
    constraint membership_invitation_phone_check check (invited_phone ~ '^\+[1-9][0-9]{9,14}$'),
    constraint membership_invitation_expiry_check check (expires_at > created_at),
    -- A response is recorded exactly when the invitation is no longer pending (EXPIRED is set by the system: no responder).
    constraint membership_invitation_lifecycle_check check (
        (status = 'PENDING' and responded_at is null and responded_by is null)
        or (status = 'EXPIRED' and responded_at is not null and responded_by is null)
        or (status in ('ACCEPTED', 'DECLINED', 'WITHDRAWN') and responded_at is not null and responded_by is not null)),
    -- Only an accepted invitation points at the membership it created.
    constraint membership_invitation_membership_check check ((status = 'ACCEPTED') = (membership_id is not null))
);

-- One open invitation per (provider, phone): re-inviting is idempotent.
create unique index uq_membership_invitation_one_pending
    on membership_invitation (provider_id, invited_phone) where status = 'PENDING';
-- "What have I been invited to" (customer side) and the provider's invitation list.
create index idx_membership_invitation_phone on membership_invitation (invited_phone, status);
create index idx_membership_invitation_provider on membership_invitation (provider_id, status, created_at desc);

comment on table membership_invitation is
    'Provider-to-phone invitation to become a customer. Becomes a provider_membership only when the invited person accepts.';
