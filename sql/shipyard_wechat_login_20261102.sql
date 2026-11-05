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
