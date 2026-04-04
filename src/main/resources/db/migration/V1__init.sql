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

create table if not exists universities (
    code varchar(32) primary key,
    name text not null,
    email text not null unique,
    contact_full_name text not null,
    password_hash varchar(128) not null,
    created_at timestamptz not null,
    active boolean not null default true
);

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
