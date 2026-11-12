-- Administrators are graded by department and form a tree. Every account except a root has a parent (the account
-- that manages it). A MANAGER may have accounts below it; a DEPARTMENT administrator is always a leaf, is limited to
-- the departments listed in admin_account_departments and cannot manage administrator accounts or roles. A child
-- never exceeds its parent: its role's functions, its departments and its applications are subsets of the parent's
-- (checked by the application, see AdminTree). Business departments themselves stay flat.
ALTER TABLE admin_accounts
 ADD COLUMN parent_username VARCHAR(100) COLLATE utf8mb4_bin NULL,
 ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'MANAGER',
 ADD COLUMN all_departments BOOLEAN NOT NULL DEFAULT TRUE,
 ADD INDEX idx_admin_accounts_parent (parent_username),
 ADD CONSTRAINT fk_admin_accounts_parent FOREIGN KEY (parent_username) REFERENCES admin_accounts(username);

-- A department that administrators are limited to cannot be deleted until they no longer are.
CREATE TABLE admin_account_departments (
 username VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 department_id BIGINT NOT NULL,
 PRIMARY KEY (username, department_id),
 INDEX idx_account_departments_department (department_id),
 CONSTRAINT fk_account_departments_account FOREIGN KEY (username) REFERENCES admin_accounts(username) ON DELETE CASCADE,
 CONSTRAINT fk_account_departments_department FOREIGN KEY (department_id) REFERENCES departments(id)
) ENGINE=InnoDB;

-- Super administrators become roots; every other existing account is placed under the first super administrator,
-- keeping all departments, so nobody's access changes.
UPDATE admin_accounts a
 JOIN (SELECT username FROM admin_accounts WHERE role='SUPER_ADMIN' ORDER BY enabled DESC, created_at, username LIMIT 1) r
 SET a.parent_username=r.username
 WHERE a.role<>'SUPER_ADMIN';
