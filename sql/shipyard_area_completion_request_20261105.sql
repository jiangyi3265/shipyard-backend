-- Leader submits area completion; the project manager confirms (completed_at) or returns it.
-- Adds nullable columns only; existing areas, records and ledgers are unchanged.
ALTER TABLE sy_area
 ADD COLUMN completion_requested_at DATETIME(3) NULL,
 ADD COLUMN completion_requested_by BIGINT NULL;
