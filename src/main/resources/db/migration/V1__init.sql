create table app_user (
    id            uuid primary key,
    phone_e164    varchar(20)  not null unique,
    display_name  varchar(80),
    role          varchar(20)  not null default 'USER',
    status        varchar(20)  not null default 'ACTIVE',
    created_at    timestamptz  not null default now()
);

create table otp_challenge (
    id          uuid primary key,
    phone_e164  varchar(20) not null,
    code_hash   varchar(64) not null,
    expires_at  timestamptz not null,
    attempts    int         not null default 0,
    consumed    boolean     not null default false,
    created_at  timestamptz not null default now()
);
create index idx_otp_phone_created on otp_challenge (phone_e164, created_at desc);

create table task_category (
    code    varchar(40) primary key,
    name    varchar(80) not null,
    active  boolean     not null default true
);

insert into task_category (code, name) values
    ('CLEANING',        'Cleaning'),
    ('PARCEL_DELIVERY', 'Parcel delivery'),
    ('SHOPPING',        'Shopping'),
    ('GARDENING',       'Gardening'),
    ('PET_CARE',        'Pet care'),
    ('ASSEMBLY',        'Furniture assembly'),
    ('OTHER',           'Other');

create table task (
    id              uuid primary key,
    requester_id    uuid          not null references app_user (id),
    provider_id     uuid          references app_user (id),
    title           varchar(120)  not null,
    description     varchar(1000),
    category_code   varchar(40)   not null references task_category (code),
    address         varchar(255)  not null,
    latitude        double precision not null,
    longitude       double precision not null,
    time_start      timestamptz   not null,
    time_end        timestamptz   not null,
    offered_amount  numeric(10,2) not null check (offered_amount > 0),
    currency        varchar(3)    not null,
    status          varchar(40)   not null,
    version         bigint        not null default 0,
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    check (time_end > time_start)
);
create index idx_task_requester on task (requester_id, created_at desc);
create index idx_task_open on task (status, category_code, time_start);

-- Append-only audit timeline
create table task_event (
    id           uuid primary key,
    task_id      uuid        not null references task (id),
    actor_id     uuid        not null references app_user (id),
    event_type   varchar(40) not null,
    from_status  varchar(40),
    to_status    varchar(40),
    created_at   timestamptz not null default now()
);
create index idx_task_event_task on task_event (task_id, created_at);
