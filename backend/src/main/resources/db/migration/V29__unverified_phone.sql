-- Phone numbers can now be ADDED without a code (email-first launch: SMS needs DLT registration). Until DLT is done an
-- unverified number is only a contact/matching hint for customers; once phone_verified is mandatory
-- (app.phone.verification-required=true) it must be proven with a code.
--
-- Every number stored so far was proven by an SMS code, so it starts verified.
alter table user_account add column phone_verified boolean not null default false;
update user_account set phone_verified = true where phone is not null;

-- An UNVERIFIED claim must never lock the real owner out, so uniqueness applies to verified numbers only.
alter table user_account drop constraint user_account_phone_unique;
create unique index uq_user_account_verified_phone on user_account (phone) where phone_verified;
create index idx_user_account_phone on user_account (phone) where phone is not null;

alter table user_account
    add constraint user_account_phone_verified_check check (phone is not null or not phone_verified);

comment on column user_account.phone_verified is 'True once the number was proven with an SMS code. Only verified numbers sign in, receive staff roles or ownership offers, and are unique.';
