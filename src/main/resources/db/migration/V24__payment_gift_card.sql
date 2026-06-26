-- A gift card can be applied to a booking payment as a payment method (reduces the Stripe charge).
ALTER TABLE payments
    ADD COLUMN gift_card_id     UUID,
    ADD COLUMN gift_card_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_gift_card
        FOREIGN KEY (gift_card_id) REFERENCES gift_cards (id) ON DELETE SET NULL;
