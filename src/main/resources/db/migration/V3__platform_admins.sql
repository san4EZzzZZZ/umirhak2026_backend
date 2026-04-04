create table if not exists platform_admins (
    login text primary key,
    full_name text not null,
    password_hash varchar(128) not null,
    active boolean not null default true,
    created_at timestamptz not null
);
