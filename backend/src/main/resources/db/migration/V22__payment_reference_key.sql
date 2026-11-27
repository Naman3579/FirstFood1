-- F-07: the duplicate-reference rule could be walked around.
--
-- V17 compared lower(btrim(reference)), but a reference may contain inner spaces and separators, so
-- "123456789012" and "1234 5678 9012" were two different references. The index also contained payment_method,
-- so one real transfer could be claimed once as UPI and again as BANK_TRANSFER.
--
-- reference_key is the reference with everything but letters and digits removed, upper-cased. It is a generated
-- column, so the database - not the application - owns the definition and no row can disagree with it. (It is also
-- why no backfill is needed: adding it rewrites the table without firing the immutability triggers from V17.)
-- PaymentReference.canonical() in Java computes the same value for the pre-insert check.
--
-- transaction_reference itself stays exactly as stored; it is immutable by trigger.

-- Fail with a readable message, not a bare "could not create unique index", if history already holds two live
-- claims on one real transfer. Those need a human decision (reject one) before this migration can run.
do $$
declare
    clashes integer;
begin
    select count(*) into clashes from (
        select upper(regexp_replace(transaction_reference, '[^A-Za-z0-9]', '', 'g'))
        from subscription_payment
        where payment_method in ('UPI', 'BANK_TRANSFER') and verification_status <> 'REJECTED'
        group by 1 having count(*) > 1) d;
    if clashes > 0 then
        raise exception 'V22: % transaction reference(s) are claimed by more than one live UPI/BANK_TRANSFER payment once spaces, separators, case and method are ignored. Reject the extra claims first; find them with: select upper(regexp_replace(transaction_reference, ''[^A-Za-z0-9]'', '''', ''g'')) k, count(*) from subscription_payment where payment_method in (''UPI'', ''BANK_TRANSFER'') and verification_status <> ''REJECTED'' group by 1 having count(*) > 1', clashes;
    end if;
end $$;

alter table subscription_payment
    add column reference_key varchar(150) generated always as (
        upper(regexp_replace(transaction_reference, '[^A-Za-z0-9]', '', 'g'))) stored;

-- A reference made only of separators would collapse to nothing and clash with every other such one.
alter table subscription_payment
    add constraint subscription_payment_reference_key_not_empty check (
        transaction_reference is null or length(reference_key) > 0);

drop index uq_subscription_payment_digital_reference;
drop index uq_subscription_payment_receipt_reference;

-- A UPI / bank reference is one real transfer: unique across the whole system, whichever method it was filed under.
-- (Same index name as before: PaymentServiceImpl recognises the violation by it.)
create unique index uq_subscription_payment_digital_reference
    on subscription_payment (reference_key)
    where payment_method in ('UPI', 'BANK_TRANSFER') and verification_status <> 'REJECTED';

-- Cash / other receipt numbers: still one receipt book per provider.
create unique index uq_subscription_payment_receipt_reference
    on subscription_payment (provider_id, payment_method, reference_key)
    where payment_method in ('CASH', 'OTHER') and transaction_reference is not null
      and verification_status <> 'REJECTED';
