-- Admin-entered Japanese base address for provider requests. Existing rows keep display-address fallback.
ALTER TABLE restaurant
    ADD COLUMN geocoding_address VARCHAR(255) NULL AFTER address;
