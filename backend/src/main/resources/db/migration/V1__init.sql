-- V1: baseline migration.
-- Intentionally creates no business tables; it only establishes Flyway's schema history
-- so every later feature adds its own V<n>__<description>.sql. Never edit a migration once merged.
SELECT 1;
