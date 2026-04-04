drop table if exists diploma_registry;

create table if not exists diploma_registry (
    id uuid primary key,
    diploma_payload_hash varchar(128) not null,
    diploma_lookup_hash varchar(128) not null unique,
    status varchar(16) not null,
    created_at timestamptz not null
);

create index if not exists idx_diploma_registry_status on diploma_registry(status);
