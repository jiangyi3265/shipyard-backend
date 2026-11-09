-- Q05/Q12/Q15/Q22 business-rule upgrade. Re-runnable; select the shipyard database before running.
-- Adds columns/tables only. Existing records, payments and balances are not changed.
SET @c=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_area' AND column_name='leader_id');
SET @s=IF(@c=0,'ALTER TABLE sy_area ADD COLUMN leader_id BIGINT NULL','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_payroll_batch' AND column_name='paid_date');
SET @s=IF(@c=0,'ALTER TABLE sy_payroll_batch ADD COLUMN paid_date DATE NULL, ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT '''', ADD COLUMN proof VARCHAR(50) NOT NULL DEFAULT '''', ADD COLUMN reversed_at DATETIME NULL','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
CREATE TABLE IF NOT EXISTS sy_ledger_file (
 ledger_id VARCHAR(36) NOT NULL, file_id VARCHAR(36) NOT NULL, PRIMARY KEY(ledger_id,file_id),
 FOREIGN KEY(ledger_id) REFERENCES sy_ledger(id), FOREIGN KEY(file_id) REFERENCES sy_file(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_ledger_reversal (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, ledger_id VARCHAR(36) NOT NULL, reason VARCHAR(500) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'pending', requested_by BIGINT NOT NULL,
 requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_by BIGINT NULL, reviewed_at DATETIME NULL,
 opinion VARCHAR(500) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,
 INDEX(ledger_id,status), FOREIGN KEY(ledger_id) REFERENCES sy_ledger(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
