create table if not exists student_verification_links (
    id uuid primary key,
    token varchar(128) not null unique,
    student_email text not null references students(email),
    diploma_lookup_hash varchar(128) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz null
);

create index if not exists idx_student_verification_links_student_email
    on student_verification_links(student_email);

create index if not exists idx_student_verification_links_expires_at
    on student_verification_links(expires_at);
