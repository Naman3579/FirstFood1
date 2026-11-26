-- Two sign-in portals on one account (customer portal "/" and staff portal "/staff").
--
-- One account per phone number; it is REGISTERED on the customer portal, the staff portal, or both. The registration
-- timestamps double as the flags (NULL = not registered). Accounts that existed before portals were introduced
-- keep working everywhere: they are registered on both.
--
-- Also: the name collected on the sign-up form, and whether the stored email has been proven with a code.

alter table user_account add column full_name varchar(120);
alter table user_account add column email_verified boolean not null default false;
alter table user_account add column customer_access_at timestamptz;
alter table user_account add column staff_access_at timestamptz;

update user_account
   set customer_access_at = created_at,
       staff_access_at = created_at;

update user_account u
   set full_name = p.full_name
  from person p
 where p.user_account_id = u.id
   and p.is_primary;

-- Email OTPs (purpose EMAIL_CHANGE) are addressed to an email, not a phone. The column keeps its historical name
-- ("phone") but now holds whichever identifier the code was sent to: a phone number, or a lower-cased email address.
alter table otp_verification alter column phone type varchar(255);
alter table otp_verification alter column new_phone type varchar(255);

alter table otp_verification drop constraint otp_verification_purpose_check;
alter table otp_verification add constraint otp_verification_purpose_check
    check (purpose in ('LOGIN', 'PHONE_CHANGE', 'EMAIL_CHANGE'));

comment on column user_account.customer_access_at is 'When the account registered on the customer portal; NULL = not registered there.';
comment on column user_account.staff_access_at is 'When the account registered on the staff portal; NULL = not registered there.';
comment on column otp_verification.phone is 'The identifier the code was sent to: a phone number, or a lower-cased email address for EMAIL_CHANGE.';
