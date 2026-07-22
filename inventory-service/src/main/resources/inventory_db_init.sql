-- NOTE: you need to execute this script manually !!!
DROP TABLE IF EXISTS inventory cascade;
DROP TABLE IF EXISTS processed_events cascade;

CREATE TABLE IF NOT EXISTS inventory
(
    order_id   uuid primary key,
    product    varchar(128) not null,
    price      numeric      not null check ( price >= 0),
    created_at TIMESTAMPTZ  not null
);

CREATE TABLE IF NOT EXISTS processed_events
(
    event_id     uuid primary key,
    processed_at TIMESTAMPTZ default now()
);

-- for future
CREATE INDEX idx_inventory_created_at ON inventory (created_at);