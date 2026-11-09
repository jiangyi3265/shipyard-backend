-- Additive upgrade for an existing shipyard schema. Select the intended database before running.
-- Existing accounts/workers remain unclassified. Existing assignments and free-text team names are preserved.
SET @sy_team_category=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_worker' AND column_name='team_category');
SET @sy_team_sql=IF(@sy_team_category=0,'ALTER TABLE sy_worker ADD COLUMN team_category VARCHAR(16) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE sy_team_stmt FROM @sy_team_sql; EXECUTE sy_team_stmt; DEALLOCATE PREPARE sy_team_stmt;
CREATE TABLE IF NOT EXISTS sy_user_team (
 user_id BIGINT PRIMARY KEY, team_category VARCHAR(16) NOT NULL DEFAULT '',
 FOREIGN KEY(user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
