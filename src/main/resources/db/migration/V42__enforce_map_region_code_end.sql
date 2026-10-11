-- ICU's $ anchor also matches before a final line terminator; \z requires the absolute end.
-- Validate existing rows without rewriting codes. Invalid rows fail this atomic ALTER.
ALTER TABLE map_region
    DROP CHECK chk_map_region_code,
    ADD CONSTRAINT chk_map_region_code CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{0,39}\\z', 'c'));
