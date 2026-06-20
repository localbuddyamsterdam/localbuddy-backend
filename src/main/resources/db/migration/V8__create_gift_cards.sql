-- Gift cards and their redemption ledger.
CREATE TABLE gift_cards (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(40) NOT NULL UNIQUE,
    initial_amount     NUMERIC(10, 2) NOT NULL CHECK (initial_amount > 0),
    balance            NUMERIC(10, 2) NOT NULL CHECK (balance >= 0),
    currency           VARCHAR(3) NOT NULL DEFAULT 'EUR',
    status             VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    purchaser_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    purchaser_email    VARCHAR(255),
    recipient_email    VARCHAR(255),
    recipient_name     VARCHAR(150),
    message            TEXT,
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE gift_card_redemptions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gift_card_id       UUID NOT NULL REFERENCES gift_cards (id) ON DELETE CASCADE,
    booking_id         UUID REFERENCES bookings (id) ON DELETE SET NULL,
    redeemed_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    amount             NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_gift_cards_purchaser ON gift_cards (purchaser_user_id);
CREATE INDEX idx_gift_card_redemptions_card ON gift_card_redemptions (gift_card_id, created_at DESC);
