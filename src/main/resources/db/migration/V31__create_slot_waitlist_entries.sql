-- Waitlist for full availability slots. When a slot is sold out, travelers and
-- guests can join the waitlist (no payment). When a seat frees up, everyone
-- waiting is emailed and can go finish a normal booking first-come-first-served.

CREATE TABLE slot_waitlist_entries (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    availability_slot_id UUID         NOT NULL,
    experience_id        UUID         NOT NULL,

    user_id              UUID,
    guest_name           VARCHAR(150),
    guest_email          VARCHAR(255),
    guest_phone          VARCHAR(40),

    guests_count         INTEGER      NOT NULL DEFAULT 1,
    status               VARCHAR(40)  NOT NULL DEFAULT 'WAITING',

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    notified_at          TIMESTAMPTZ,

    CONSTRAINT fk_waitlist_slot
        FOREIGN KEY (availability_slot_id) REFERENCES availability_slots (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_user
        FOREIGN KEY (user_id) REFERENCES users (id),

    CONSTRAINT chk_waitlist_user_or_guest
        CHECK (
            user_id IS NOT NULL
                OR (guest_name IS NOT NULL AND guest_email IS NOT NULL AND guest_phone IS NOT NULL)
            ),
    CONSTRAINT chk_waitlist_guests_positive
        CHECK (guests_count > 0)
);

CREATE INDEX idx_waitlist_slot_status ON slot_waitlist_entries (availability_slot_id, status);
CREATE INDEX idx_waitlist_user ON slot_waitlist_entries (user_id);
CREATE INDEX idx_waitlist_guest_email ON slot_waitlist_entries (LOWER(guest_email));

-- A person can only have one active waitlist entry per slot.
CREATE UNIQUE INDEX ux_waitlist_active_user_slot
    ON slot_waitlist_entries (user_id, availability_slot_id)
    WHERE user_id IS NOT NULL AND status IN ('WAITING', 'NOTIFIED');

CREATE UNIQUE INDEX ux_waitlist_active_guest_slot
    ON slot_waitlist_entries (LOWER(guest_email), availability_slot_id)
    WHERE user_id IS NULL AND status IN ('WAITING', 'NOTIFIED');
