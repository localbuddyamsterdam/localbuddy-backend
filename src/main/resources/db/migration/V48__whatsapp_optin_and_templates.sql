-- WhatsApp utility notifications, done consentfully:
--  * bookings.whatsapp_opt_in — explicit per-booking consent captured at checkout ("send my
--    booking updates on WhatsApp"). Default FALSE: nobody gets WhatsApp messages who didn't ask.
--  * notifications.wa_template / wa_params — when set on a WHATSAPP-channel notification, the
--    processor sends the named Meta-approved template with these body parameters (JSON array of
--    strings, filling {{1}}..{{n}} in order) instead of free-form text. Business-initiated
--    messages only deliver as templates; free-form remains for open-session replies.

ALTER TABLE bookings ADD COLUMN whatsapp_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE notifications ADD COLUMN wa_template VARCHAR(120);
ALTER TABLE notifications ADD COLUMN wa_params VARCHAR(2000);
