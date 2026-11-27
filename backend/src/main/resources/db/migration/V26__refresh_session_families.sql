-- F-11: session families and revocation reasons.
--
-- family_id: one login = one family. Every rotation keeps the family, so "this session" survives refreshes and a replayed
-- (already rotated) token can revoke the WHOLE session instead of just failing. The access token carries the family id
-- (claim "sid"), which is how logout and revocation end access tokens before their 15 minutes are up.
-- revoked_reason: why a row stopped being usable. Only ROTATED rows are a theft signal when presented again.
-- Existing rows: each is its own family (they were never linked), and anything already revoked is LEGACY (cause unknown,
-- so it never triggers a family revocation).

alter table refresh_session add column family_id uuid;
update refresh_session set family_id = id where family_id is null;
alter table refresh_session alter column family_id set not null;

alter table refresh_session add column revoked_reason varchar(20);
update refresh_session set revoked_reason = 'LEGACY' where revoked_at is not null;

alter table refresh_session add constraint refresh_session_revoked_reason_valid
    check (revoked_reason is null or revoked_reason in ('ROTATED', 'LOGOUT', 'REUSE', 'PHONE_CHANGE', 'LEGACY'));
alter table refresh_session add constraint refresh_session_revoked_pair
    check ((revoked_at is null) = (revoked_reason is null));

-- Hot path: every authenticated request asks "does this family still have a live row?".
create index idx_refresh_session_family_live on refresh_session (family_id) where revoked_at is null;
create index idx_refresh_session_family on refresh_session (family_id);
