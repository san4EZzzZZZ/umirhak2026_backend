-- Compatibility migration from old schema to current 4-table model.

-- 1) universities: ensure required columns exist
alter table if exists universities add column if not exists email text;
alter table if exists universities add column if not exists contact_full_name text;
alter table if exists universities add column if not exists password_hash varchar(128);
alter table if exists universities add column if not exists active boolean;

-- Fill defaults for existing rows so app queries don't fail.
update universities
set email = lower(code) || '@placeholder.local'
where email is null or btrim(email) = '';

update universities
set contact_full_name = name
where contact_full_name is null or btrim(contact_full_name) = '';

update universities
set password_hash = 'legacy-not-set'
where password_hash is null or btrim(password_hash) = '';

update universities
set active = true
where active is null;

-- Keep created_at if exists; if missing create with current timestamp.
alter table if exists universities add column if not exists created_at timestamptz;
update universities
set created_at = now()
where created_at is null;

-- Old column is not used anymore.
alter table if exists universities drop column if exists signing_secret;

-- 2) create required account tables
create table if not exists students (
    email text primary key,
    full_name text not null,
    password_hash varchar(128) not null,
    created_at timestamptz not null
);

create table if not exists hr_specialists (
    email text primary key,
    full_name text not null,
    password_hash varchar(128) not null,
    created_at timestamptz not null
);

-- 3) create new diploma table
create table if not exists diploma_registry (
    id uuid primary key,
    university_code varchar(32) not null references universities(code),
    full_name_enc text not null,
    specialty_enc text not null,
    graduation_year int not null,
    diploma_code_enc text not null,
    diploma_payload_hash varchar(128) not null,
    diploma_lookup_hash varchar(128) not null unique,
    status varchar(16) not null,
    created_at timestamptz not null,
    revoked_at timestamptz null
);

create index if not exists idx_diploma_registry_university_code on diploma_registry(university_code);
create index if not exists idx_diploma_registry_status on diploma_registry(status);

-- 4) if old diplomas table exists, migrate rows into new table once
do $$
begin
    if exists (select 1 from information_schema.tables where table_name = 'diplomas') then
        execute '
            insert into diploma_registry(
                id,
                university_code,
                full_name_enc,
                specialty_enc,
                graduation_year,
                diploma_code_enc,
                diploma_payload_hash,
                diploma_lookup_hash,
                status,
                created_at,
                revoked_at
            )
            select
                d.id,
                d.university_code,
                d.full_name_enc,
                d.specialty_enc,
                d.graduation_year,
                d.diploma_number_enc,
                d.record_hash,
                d.diploma_number_hash,
                d.status,
                d.created_at,
                d.revoked_at
            from diplomas d
            on conflict (diploma_lookup_hash) do nothing
        ';
    end if;
end
$$;

-- 5) Optional legacy tables may remain; app no longer depends on them.
