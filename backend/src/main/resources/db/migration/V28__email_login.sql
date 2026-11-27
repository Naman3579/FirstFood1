-- Email sign-up / sign-in (SMS OTP needs DLT registration in India, so email is the first way in).
--
-- An account is now identified by a verified email OR a phone number (or both). Phone stays unique when present;
-- Postgres lets any number of NULLs through a unique constraint, so email-only accounts do not collide.

-- Emails are compared lower-cased everywhere in Java; make the stored form match.
update user_account set email = lower(btrim(email)) where email is not null;

-- One verified email = one account. Only VERIFIED emails are unique: the old PATCH /me/email stores an unproven
-- address, and letting that claim a login identity would let anyone block someone else's address.
do $$
declare
    clashes integer;
begin
    select count(*) into clashes from (
        select email from user_account where email_verified group by email having count(*) > 1) d;
    if clashes > 0 then
        raise exception 'V28: % verified email(s) belong to more than one account. Decide which account keeps each address, clear the others (update user_account set email = null, email_verified = false where id = ...), then re-run. Find them with: select email, array_agg(id) from user_account where email_verified group by email having count(*) > 1', clashes;
    end if;
end $$;

create unique index uq_user_account_verified_email on user_account (email) where email_verified;

alter table user_account alter column phone drop not null;

-- Every account must be reachable by something we can sign it in with.
alter table user_account
    add constraint user_account_identity_check check (phone is not null or (email is not null and email_verified));

comment on column user_account.phone is 'E.164 number; NULL for accounts that signed up with a verified email only.';
