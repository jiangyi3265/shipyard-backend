-- Match subscription consent precision so progress in the same second is not lost.
-- Existing timestamps and business values are preserved.
ALTER TABLE sy_audit MODIFY created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
