-- V11: VAT invoices / receipts with sequential per-year numbering.
-- Three document types: COMMISSION (platform -> host), SERVICE_FEE_RECEIPT
-- (platform -> customer), PAYOUT_STATEMENT (platform -> host, explaining the
-- net transfer). Issuer details are snapshotted so issued invoices are immutable.

CREATE TABLE invoice_sequences (
    year        INTEGER PRIMARY KEY,
    last_number INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE invoices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number       VARCHAR(40)  NOT NULL UNIQUE,
    invoice_type         VARCHAR(30)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',

    issuer_name          VARCHAR(200) NOT NULL,
    issuer_vat_number    VARCHAR(40),
    issuer_address       TEXT,
    issuer_country       VARCHAR(2),

    recipient_type       VARCHAR(20)  NOT NULL,        -- HOST | CUSTOMER
    recipient_name       VARCHAR(200),
    recipient_email      VARCHAR(255),
    recipient_vat_number VARCHAR(40),
    recipient_address    TEXT,

    local_profile_id     UUID,
    booking_id           UUID,
    payment_id           UUID,
    payout_id            UUID,

    currency             VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    subtotal_amount      NUMERIC(12, 2) NOT NULL DEFAULT 0,   -- net
    vat_amount           NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    vat_note             TEXT,

    pdf_storage_key      TEXT,
    issued_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_invoices_local_profile FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id),
    CONSTRAINT fk_invoices_booking       FOREIGN KEY (booking_id)       REFERENCES bookings (id),
    CONSTRAINT fk_invoices_payment       FOREIGN KEY (payment_id)       REFERENCES payments (id),
    CONSTRAINT fk_invoices_payout        FOREIGN KEY (payout_id)        REFERENCES payouts (id)
);

CREATE INDEX idx_invoices_type          ON invoices (invoice_type);
CREATE INDEX idx_invoices_local_profile ON invoices (local_profile_id);
CREATE INDEX idx_invoices_booking       ON invoices (booking_id);
CREATE INDEX idx_invoices_payout        ON invoices (payout_id);

CREATE TABLE invoice_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      UUID NOT NULL,
    description     TEXT NOT NULL,
    quantity        NUMERIC(10, 2) NOT NULL DEFAULT 1,
    unit_net_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    net_amount      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    vat_rate        NUMERIC(5, 4)  NOT NULL DEFAULT 0,
    vat_amount      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    sort_order      INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_lines_invoice ON invoice_lines (invoice_id);
