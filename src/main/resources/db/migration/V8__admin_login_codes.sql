create table if not exists admin_login_codes (
    id uuid primary key,
    admin_login text not null references platform_admins(login) on delete cascade,
    code_hash varchar(128) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz null
);

create index if not exists idx_admin_login_codes_admin_login
    on admin_login_codes(admin_login);

create index if not exists idx_admin_login_codes_expires_at
    on admin_login_codes(expires_at);

