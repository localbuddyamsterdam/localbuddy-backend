-- Option B: a persistent, editable weekly availability pattern (the host's
-- source of truth). Concrete bookable rows still live in availability_slots,
-- which a scheduled job materialises from these schedules up to a horizon.
CREATE TABLE availability_schedules (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experience_id      UUID        NOT NULL,
    local_profile_id   UUID        NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    capacity           INTEGER     NOT NULL DEFAULT 1,
    private_eligible   BOOLEAN     NOT NULL DEFAULT FALSE,
    timezone           VARCHAR(64) NOT NULL DEFAULT 'Europe/Amsterdam',
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    materialized_until DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_date_range CHECK (end_date >= start_date),
    CONSTRAINT chk_schedule_capacity   CHECK (capacity > 0),
    CONSTRAINT fk_schedule_experience  FOREIGN KEY (experience_id)    REFERENCES experiences (id)    ON DELETE CASCADE,
    CONSTRAINT fk_schedule_profile     FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id) ON DELETE CASCADE
);

CREATE INDEX idx_avail_schedules_experience ON availability_schedules (experience_id);
CREATE INDEX idx_avail_schedules_profile    ON availability_schedules (local_profile_id);
CREATE INDEX idx_avail_schedules_status     ON availability_schedules (status);

-- The weekly pattern: one row per (day-of-week, start-time). Times are
-- wall-clock in the schedule's timezone.
CREATE TABLE availability_schedule_times (
    schedule_id UUID        NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time  TIME        NOT NULL,
    CONSTRAINT fk_schedule_time_schedule FOREIGN KEY (schedule_id) REFERENCES availability_schedules (id) ON DELETE CASCADE
);

CREATE INDEX idx_avail_schedule_times_schedule ON availability_schedule_times (schedule_id);

-- Link each materialised slot back to the pattern that produced it, and carry
-- private-buyout eligibility down onto the concrete slot.
ALTER TABLE availability_slots
    ADD COLUMN source_schedule_id UUID,
    ADD COLUMN private_eligible   BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE availability_slots
    ADD CONSTRAINT fk_slot_source_schedule
        FOREIGN KEY (source_schedule_id) REFERENCES availability_schedules (id) ON DELETE SET NULL;

CREATE INDEX idx_availability_slots_source_schedule ON availability_slots (source_schedule_id);
