alter table student_verification_links
    add column if not exists holder_full_name text;

alter table student_verification_links
    add column if not exists university_code varchar(64);

alter table student_verification_links
    add column if not exists specialty text;

