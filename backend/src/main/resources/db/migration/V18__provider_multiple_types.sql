-- A provider can be several kinds of food business at once (e.g. a MESS that also runs a TIFFIN service).
-- The single food_provider.provider_type column becomes a set of types in its own table.

create table food_provider_type (
    provider_id uuid not null references food_provider (id),
    provider_type varchar(20) not null,
    primary key (provider_id, provider_type),
    constraint food_provider_type_value_check check (
        provider_type in ('MESS', 'TIFFIN', 'PG', 'HOSTEL', 'CORPORATE', 'OTHER'))
);

insert into food_provider_type (provider_id, provider_type)
select id, provider_type from food_provider;

create index idx_food_provider_type_type on food_provider_type (provider_type, provider_id);

alter table food_provider drop constraint food_provider_type_check;
alter table food_provider drop column provider_type;
