-- Additive migration: run AFTER the RuoYi schema, in a dedicated database.
CREATE TABLE IF NOT EXISTS sy_project (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(80) NOT NULL UNIQUE,
 title VARCHAR(120) NOT NULL, price DECIMAL(14,2) NOT NULL, area DECIMAL(14,2) NOT NULL,
 overtime_rate DECIMAL(6,2) NOT NULL DEFAULT 1.00, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_project_member (
 project_id BIGINT NOT NULL, user_id BIGINT NOT NULL, PRIMARY KEY(project_id,user_id),
 FOREIGN KEY(project_id) REFERENCES sy_project(id), FOREIGN KEY(user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_area (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, project_id BIGINT NOT NULL, name VARCHAR(80) NOT NULL,
 area DECIMAL(14,2) NOT NULL, rate DECIMAL(12,2) NOT NULL, completed_at DATETIME NULL,
 completion_requested_at DATETIME(3) NULL, completion_requested_by BIGINT NULL,
 UNIQUE(project_id,name), FOREIGN KEY(project_id) REFERENCES sy_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing databases: add the leader completion-request columns once (re-runnable).
SET @sy_area_request=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_area' AND column_name='completion_requested_at');
SET @sy_area_sql=IF(@sy_area_request=0,'ALTER TABLE sy_area ADD COLUMN completion_requested_at DATETIME(3) NULL, ADD COLUMN completion_requested_by BIGINT NULL','SELECT 1');
PREPARE sy_area_stmt FROM @sy_area_sql; EXECUTE sy_area_stmt; DEALLOCATE PREPARE sy_area_stmt;
CREATE TABLE IF NOT EXISTS sy_worker (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, code VARCHAR(32) NOT NULL UNIQUE,
 team VARCHAR(50) NOT NULL, rate DECIMAL(12,2) NOT NULL, active TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Classification is independent of the legacy free-text team name and all access rules.
SET @sy_team_category=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_worker' AND column_name='team_category');
SET @sy_team_sql=IF(@sy_team_category=0,'ALTER TABLE sy_worker ADD COLUMN team_category VARCHAR(16) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE sy_team_stmt FROM @sy_team_sql; EXECUTE sy_team_stmt; DEALLOCATE PREPARE sy_team_stmt;
CREATE TABLE IF NOT EXISTS sy_user_team (
 user_id BIGINT PRIMARY KEY, team_category VARCHAR(16) NOT NULL DEFAULT '',
 FOREIGN KEY(user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_crew (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, category VARCHAR(16) NOT NULL,
 leader_id BIGINT NULL, leader_name VARCHAR(30) NOT NULL, version INT NOT NULL DEFAULT 0,
 UNIQUE(category,name), FOREIGN KEY(leader_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @sy_crew_column=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sy_worker' AND column_name='crew_id');
SET @sy_crew_sql=IF(@sy_crew_column=0,'ALTER TABLE sy_worker ADD COLUMN crew_id BIGINT NULL, ADD CONSTRAINT fk_worker_crew FOREIGN KEY(crew_id) REFERENCES sy_crew(id)','SELECT 1');
PREPARE sy_crew_stmt FROM @sy_crew_sql; EXECUTE sy_crew_stmt; DEALLOCATE PREPARE sy_crew_stmt;
CREATE TABLE IF NOT EXISTS sy_record (
 id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL, area_id BIGINT NULL,
 kind VARCHAR(12) NOT NULL, type VARCHAR(12) NOT NULL DEFAULT '', title VARCHAR(100) NOT NULL,
 amount DECIMAL(14,2) NOT NULL, status VARCHAR(30) NOT NULL, business_date DATE NOT NULL,
 created_by BIGINT NOT NULL, body JSON NOT NULL, version INT NOT NULL DEFAULT 0,
 request_key VARCHAR(64) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE(created_by,request_key), INDEX(project_id,status), INDEX(business_date,kind),
 FOREIGN KEY(project_id) REFERENCES sy_project(id), FOREIGN KEY(area_id) REFERENCES sy_area(id),
 FOREIGN KEY(created_by) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_payroll_batch (
 id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL, month VARCHAR(7) NOT NULL,
 amount DECIMAL(14,2) NOT NULL, paid_by BIGINT NOT NULL, paid_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 request_key VARCHAR(64) NOT NULL UNIQUE, FOREIGN KEY(project_id) REFERENCES sy_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_work_line (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, record_id VARCHAR(36) NOT NULL, worker_id BIGINT NOT NULL,
 worker_name VARCHAR(50) NOT NULL, hours DECIMAL(6,2) NOT NULL DEFAULT 0,
 rate DECIMAL(12,2) NOT NULL, multiplier DECIMAL(6,2) NOT NULL DEFAULT 1,
 amount DECIMAL(14,2) NOT NULL, payment_id VARCHAR(36) NULL,
 UNIQUE(record_id,worker_id), INDEX(worker_id), FOREIGN KEY(record_id) REFERENCES sy_record(id),
 FOREIGN KEY(worker_id) REFERENCES sy_worker(id), FOREIGN KEY(payment_id) REFERENCES sy_payroll_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_ledger (
 id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL,
 amount DECIMAL(14,2) NOT NULL, business_date DATE NOT NULL, note VARCHAR(500) NOT NULL DEFAULT '',
 source_kind VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL, created_by BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(source_kind,source_id), FOREIGN KEY(project_id) REFERENCES sy_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, record_id VARCHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 actor_name VARCHAR(50) NOT NULL, action VARCHAR(40) NOT NULL, from_status VARCHAR(30) NOT NULL,
 to_status VARCHAR(30) NOT NULL, opinion VARCHAR(500) NOT NULL DEFAULT '', snapshot JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), INDEX(record_id), FOREIGN KEY(record_id) REFERENCES sy_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_file (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, mime VARCHAR(40) NOT NULL,
 content MEDIUMBLOB NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_record_file (
 record_id VARCHAR(36) NOT NULL, file_id VARCHAR(36) NOT NULL, PRIMARY KEY(record_id,file_id),
 FOREIGN KEY(record_id) REFERENCES sy_record(id), FOREIGN KEY(file_id) REFERENCES sy_file(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_notice_read (
 user_id BIGINT NOT NULL, audit_id BIGINT NOT NULL, PRIMARY KEY(user_id,audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive migration. Does not change existing users, roles or business records.
CREATE TABLE IF NOT EXISTS sy_wechat_registration (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 real_name VARCHAR(30) NOT NULL,
 phone VARCHAR(24) NOT NULL DEFAULT '',
 note VARCHAR(300) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'pending',
 user_id BIGINT NULL,
 review_reason VARCHAR(300) NOT NULL DEFAULT '',
 reviewed_by BIGINT NULL,
 reviewed_at DATETIME NULL,
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE(app_id,open_id), UNIQUE(user_id), INDEX(status,created_at),
 FOREIGN KEY(user_id) REFERENCES sys_user(user_id),
 FOREIGN KEY(reviewed_by) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sy_wechat_subscription (
 user_id BIGINT NOT NULL,
 template_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 credits INT NOT NULL DEFAULT 0,
 request_key VARCHAR(64) NOT NULL,
 authorized_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sy_wechat_delivery (
 audit_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 error_code INT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(audit_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
