-- A customer's address, kept on their person profile (what providers see on the customer list, e.g. for tiffin delivery).
-- All optional: a customer who has not entered one simply has none. Either the whole address (line, locality, city)
-- is present or none of it is; the pincode is optional on top of that.
alter table person
    add column address_line varchar(255),
    add column locality varchar(120),
    add column city varchar(100),
    add column pincode varchar(6),
    add column landmark varchar(150);

alter table person
    add constraint person_address_complete_check check (
        (address_line is null and locality is null and city is null and pincode is null and landmark is null)
        or (address_line is not null and length(btrim(address_line)) > 0
            and locality is not null and length(btrim(locality)) > 0
            and city is not null and length(btrim(city)) > 0)),
    add constraint person_pincode_check check (pincode is null or pincode ~ '^[1-9][0-9]{5}$');
