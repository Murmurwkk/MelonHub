-- Console permissions are configured by function. Every console endpoint belongs to one function (declared in code,
-- see AdminFunction), and an administrator role is a set of functions. The four former roles become built-in roles
-- whose functions reproduce exactly the access they had; further roles can be defined in the console.
CREATE TABLE admin_functions (
 code VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 category VARCHAR(50) NOT NULL,
 sort_order INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO admin_functions(code, name, category, sort_order) VALUES
 ('org.view', '查看部门、用户与软件', '组织与软件', 10),
 ('department.manage', '维护部门', '组织与软件', 20),
 ('user.manage', '维护用户（含批量导入、强制下线）', '组织与软件', 30),
 ('application.manage', '维护软件（含限流）', '组织与软件', 40),
 ('application.credentials', '管理 API Key 与 Webhook', '组织与软件', 50),
 ('domain.view', '查看授权配置', '授权管理', 60),
 ('domain.configure', '配置角色与权限', '授权管理', 70),
 ('domain.assign', '分配用户角色', '授权管理', 80),
 ('domain.transfer', '复制配置与导入清单', '授权管理', 90),
 ('governance.view', '查看审批与复核', '授权治理', 100),
 ('request.approve', '审批角色申请', '授权治理', 110),
 ('review.manage', '发起与处理权限复核', '授权治理', 120),
 ('audit.view', '查看与校验审计日志', '安全与审计', 130),
 ('system.view', '查看系统状态', '安全与审计', 140),
 ('account.manage', '管理管理员账号', '安全与审计', 150),
 ('role.manage', '管理管理员角色', '安全与审计', 160);

CREATE TABLE admin_roles (
 code VARCHAR(32) COLLATE utf8mb4_bin PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',
 builtin BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
INSERT INTO admin_roles(code, name, description, builtin) VALUES
 ('SUPER_ADMIN', '超级管理员', '全部功能，包括管理员账号与管理员角色', TRUE),
 ('ADMIN', '平台管理员', '维护部门、用户、软件和全部授权配置，不能管理管理员账号', TRUE),
 ('APP_ADMIN', '软件管理员', '管理软件的角色、权限、用户授权、审批、复核、清单、API Key 和 Webhook；部门与用户只读', TRUE),
 ('AUDITOR', '审计员', '只读查看全部配置、审批复核与审计日志', TRUE);

CREATE TABLE admin_role_functions (
 role_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
 function_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY (role_code, function_code),
 CONSTRAINT fk_role_functions_role FOREIGN KEY (role_code) REFERENCES admin_roles(code) ON DELETE CASCADE,
 CONSTRAINT fk_role_functions_function FOREIGN KEY (function_code) REFERENCES admin_functions(code)
) ENGINE=InnoDB;
INSERT INTO admin_role_functions(role_code, function_code) SELECT 'SUPER_ADMIN', code FROM admin_functions;
INSERT INTO admin_role_functions(role_code, function_code)
 SELECT 'ADMIN', code FROM admin_functions WHERE code NOT IN ('account.manage', 'role.manage');
INSERT INTO admin_role_functions(role_code, function_code)
 SELECT 'APP_ADMIN', code FROM admin_functions WHERE code IN ('org.view', 'application.credentials', 'domain.view', 'domain.configure',
  'domain.assign', 'domain.transfer', 'governance.view', 'request.approve', 'review.manage', 'audit.view', 'system.view');
INSERT INTO admin_role_functions(role_code, function_code)
 SELECT 'AUDITOR', code FROM admin_functions WHERE code IN ('org.view', 'domain.view', 'governance.view', 'audit.view', 'system.view');

-- Only APP_ADMIN accounts were limited to their assigned applications, implied by the role. Roles are now
-- configurable, so the limit is stored per account.
ALTER TABLE admin_accounts ADD COLUMN all_applications BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE admin_accounts SET all_applications=FALSE WHERE role='APP_ADMIN';
ALTER TABLE admin_accounts MODIFY role VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'AUDITOR';
ALTER TABLE admin_accounts ADD CONSTRAINT fk_admin_accounts_role FOREIGN KEY (role) REFERENCES admin_roles(code);
