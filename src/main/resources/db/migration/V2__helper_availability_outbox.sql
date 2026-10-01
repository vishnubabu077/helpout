-- A helper's "ready to help" session. PostgreSQL is the source of truth for availability and assignment;
-- Redis only mirrors AVAILABLE rows to find candidates quickly.
create table helper_availability (
    id                 uuid primary key,
    helper_id          uuid             not null references app_user (id),
    status             varchar(20)      not null check (status in ('AVAILABLE', 'OFFERED', 'ASSIGNED', 'OFFLINE')),
    latitude           double precision not null,
    longitude          double precision not null,
    category_codes     text[]           not null,
    max_distance_m     int              not null check (max_distance_m > 0),
    current_task_id    uuid             references task (id),
    offer_expires_at   timestamptz,
    last_heartbeat_at  timestamptz      not null,
    version            bigint           not null default 1,
    created_at         timestamptz      not null default now(),
    updated_at         timestamptz      not null default now()
);

-- A helper has at most one live row; OFFLINE rows are history and may repeat.
create unique index uq_helper_availability_active
    on helper_availability (helper_id)
    where status in ('AVAILABLE', 'OFFERED', 'ASSIGNED');

-- Heartbeat sweeper and reservation freshness checks.
create index idx_helper_availability_status_heartbeat on helper_availability (status, last_heartbeat_at);
-- Offer expiry sweeper.
create index idx_helper_availability_offer_expiry on helper_availability (offer_expires_at) where status = 'OFFERED';
-- Releasing a helper when a task closes.
create index idx_helper_availability_task on helper_availability (current_task_id) where current_task_id is not null;

-- Transactional outbox: written in the same transaction as the business row, relayed to Kafka afterwards.
create table outbox_event (
    id              uuid primary key,
    aggregate_type  varchar(60)  not null,
    aggregate_id    uuid         not null,
    event_type      varchar(60)  not null,
    partition_key   varchar(100) not null,
    payload         jsonb        not null,
    created_at      timestamptz  not null default now(),
    published_at    timestamptz
);
create index idx_outbox_unpublished on outbox_event (created_at, id) where published_at is null;
