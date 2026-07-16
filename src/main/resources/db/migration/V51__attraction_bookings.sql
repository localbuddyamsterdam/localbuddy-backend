-- Third-party attraction ticket orders (Tiqets distributor API). Only in-app bookings are
-- recorded — affiliate link-out sales happen entirely on the provider's site. Requires login,
-- so user_id is NOT NULL; RESTRICT (default) on delete keeps the financial trail intact.

CREATE TABLE attraction_bookings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL DEFAULT 'TIQETS',
    product_id VARCHAR(80) NOT NULL,
    product_title VARCHAR(200) NOT NULL,
    city_slug VARCHAR(120),
    visit_date DATE NOT NULL,
    timeslot VARCHAR(60),
    quantity INT NOT NULL,
    total_amount NUMERIC(10, 2),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider_order_id VARCHAR(120),
    ticket_url VARCHAR(1000),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_attraction_bookings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_attraction_bookings_quantity CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT chk_attraction_bookings_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_attraction_bookings_user ON attraction_bookings (user_id, created_at DESC);
