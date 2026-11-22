-- Customer-side discovery of nearby providers.
--
-- latitude/longitude: where the provider is (decimal degrees, WGS84). Optional - a provider without coordinates is
-- still found by city/locality search, it just has no distance. Both or neither.
-- food_type: what the kitchen serves. The customer filter "food type" is this column.

alter table food_provider add column latitude numeric(9, 6);
alter table food_provider add column longitude numeric(9, 6);
alter table food_provider add column food_type varchar(20) not null default 'VEG_AND_NON_VEG';

alter table food_provider add constraint food_provider_food_type_check
    check (food_type in ('VEG', 'NON_VEG', 'VEG_AND_NON_VEG', 'JAIN', 'VEGAN'));
alter table food_provider add constraint food_provider_coordinates_check
    check ((latitude is null and longitude is null)
        or (latitude is not null and longitude is not null
            and latitude between -90 and 90 and longitude between -180 and 180));

-- The nearby search first narrows by a latitude/longitude bounding box, then ranks by exact distance.
create index idx_food_provider_geo on food_provider (latitude, longitude)
    where status <> 'CLOSED' and latitude is not null;
create index idx_food_provider_city on food_provider (lower(city))
    where status <> 'CLOSED';
