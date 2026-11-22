-- F-13: "+919876543210" and "919876543210" were two different strings, so one SIM could own two accounts (and a staff
-- invitation had to try both spellings). The one stored form is now "+" followed by the digits.
-- PhonePattern.addPlus / canonical in Java produce the same form on every write and lookup.

-- Two accounts that differ only by the '+' are one person and need a human decision (which one keeps the history), so
-- fail with a readable message instead of a bare unique-violation halfway through the rewrite.
do $$
declare
    clashes integer;
    malformed integer;
begin
    select count(*) into clashes from (
        select ltrim(phone, '+') from user_account group by 1 having count(*) > 1) d;
    if clashes > 0 then
        raise exception 'V27: % phone number(s) exist as two accounts, one written with "+" and one without. Merge or suspend-and-remove one account of each pair, then re-run. Find them with: select ltrim(phone, ''+'') k, array_agg(id) from user_account group by 1 having count(*) > 1', clashes;
    end if;

    select count(*) into malformed from user_account where phone !~ '^\+?[1-9][0-9]{9,14}$';
    if malformed > 0 then
        raise exception 'V27: % account(s) have a phone number that is not 10-15 digits with an optional leading "+". Fix them first; find them with: select id, phone from user_account where phone !~ ''^\+?[1-9][0-9]{9,14}$''', malformed;
    end if;
end $$;

update user_account set phone = '+' || phone where phone not like '+%';

-- Keep the one-account-per-SIM rule from ever being bypassed by a spelling again.
alter table user_account
    add constraint user_account_phone_e164_check check (phone ~ '^\+[1-9][0-9]{9,14}$');

-- Pending login / phone-change codes are keyed by the same string, so they follow (email codes never match: they are
-- not bare digits). They expire in minutes; this only keeps a code requested just before the deploy usable.
update otp_verification set phone = '+' || phone where phone ~ '^[1-9][0-9]{9,14}$';
update otp_verification set new_phone = '+' || new_phone where new_phone ~ '^[1-9][0-9]{9,14}$';
