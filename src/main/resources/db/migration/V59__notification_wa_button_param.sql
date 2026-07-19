-- Dynamic URL suffixes for a WhatsApp template's URL buttons, in button-index order (index 0
-- first, etc.) — e.g. ["LB-48213", "Noordermarkt%2C%20Amsterdam"] for a "Manage my booking" +
-- "Open in Maps" pair. JSON array of strings, same convention as wa_params. Null when the
-- template has no URL buttons.
ALTER TABLE notifications ADD COLUMN wa_button_params VARCHAR(500);
