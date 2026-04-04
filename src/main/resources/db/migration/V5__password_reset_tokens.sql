create table if not exists password_reset_tokens (
    id uuid primary key,
    token_hash varchar(128) not null unique,
    account_role varchar(32) not null,
    account_login text not null,
    account_email text not null,
    expires_at timestamptz not null,
    used_at timestamptz null,
    created_at timestamptz not null
);

create index if not exists idx_password_reset_tokens_expires_at on password_reset_tokens(expires_at);
create index if not exists idx_password_reset_tokens_role_login on password_reset_tokens(account_role, account_login);
