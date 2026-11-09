-- Named crews: run in the explicitly selected shipyard database after shipyard_team_category.sql.
-- No historical staff assignment is inferred or changed by this re-runnable migration.
CREATE TABLE IF NOT EXISTS sy_crew (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, category VARCHAR(16) NOT NULL,
 leader_id BIGINT NULL, leader_name VARCHAR(30) NOT NULL, version INT NOT NULL DEFAULT 0,
 UNIQUE(category,name), FOREIGN KEY(leader_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @sy_crew_column=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_worker' AND column_name='crew_id');
SET @sy_crew_sql=IF(@sy_crew_column=0,'ALTER TABLE sy_worker ADD COLUMN crew_id BIGINT NULL, ADD CONSTRAINT fk_worker_crew FOREIGN KEY(crew_id) REFERENCES sy_crew(id)','SELECT 1');
PREPARE sy_crew_stmt FROM @sy_crew_sql; EXECUTE sy_crew_stmt; DEALLOCATE PREPARE sy_crew_stmt;
