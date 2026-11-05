-- Overtime is paid at the worker's normal hourly rate.
-- Run only in this project's database. Existing wage snapshots and settlements are preserved.
ALTER TABLE sy_project ALTER COLUMN overtime_rate SET DEFAULT 1.00;
UPDATE sy_project SET overtime_rate=1.00 WHERE overtime_rate<>1.00;
