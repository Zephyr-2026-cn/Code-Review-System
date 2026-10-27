-- =====================================================================
-- V1  系统域初始化：RBAC 权限模型 + 审计日志
-- 约定：
--   1) 所有时间列使用 TIMESTAMPTZ，避免跨时区部署时出现 8 小时偏差
--   2) 逻辑删除统一用 deleted SMALLINT(0/1)，唯一约束一律建成"部分唯一索引"，
--      否则被逻辑删除的记录会永久占用用户名/编码
--   3) 主键统一 BIGSERIAL，不使用 UUID，便于与审计日志、问题表做范围查询
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------- 用户
CREATE TABLE IF NOT EXISTS sys_user
(
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password      VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON COLUMN sys_user.password IS 'BCrypt 摘要，禁止存明文';
COMMENT ON COLUMN sys_user.status IS 'ENABLED / DISABLED';

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user (username) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user (status) WHERE deleted = 0;

-- ---------------------------------------------------------------- 角色
CREATE TABLE IF NOT EXISTS sys_role
(
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_code ON sys_role (code) WHERE deleted = 0;

-- ---------------------------------------------------------------- 用户-角色
CREATE TABLE IF NOT EXISTS sys_user_role
(
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role ON sys_user_role (user_id, role_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_role_role ON sys_user_role (role_id);

-- ---------------------------------------------------------------- 权限点
CREATE TABLE IF NOT EXISTS sys_permission
(
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    parent_id  BIGINT       REFERENCES sys_permission (id) ON DELETE SET NULL,
    path       VARCHAR(128),
    icon       VARCHAR(64),
    sort_order INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN sys_permission.code IS '权限编码，格式 域:资源:动作，例如 task:create';
COMMENT ON COLUMN sys_permission.type IS 'MENU / API / BUTTON';

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_permission_code ON sys_permission (code);
CREATE INDEX IF NOT EXISTS idx_sys_permission_parent ON sys_permission (parent_id);

-- ---------------------------------------------------------------- 角色-权限
CREATE TABLE IF NOT EXISTS sys_role_permission
(
    id            BIGSERIAL PRIMARY KEY,
    role_id       BIGINT NOT NULL REFERENCES sys_role (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES sys_permission (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_permission ON sys_role_permission (role_id, permission_id);

-- ---------------------------------------------------------------- 审计日志
CREATE TABLE IF NOT EXISTS sys_audit_log
(
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT,
    username    VARCHAR(64),
    action      VARCHAR(64)  NOT NULL,
    resource    VARCHAR(64),
    resource_id VARCHAR(64),
    detail      JSONB,
    ip          VARCHAR(64),
    user_agent  VARCHAR(255),
    success     BOOLEAN      NOT NULL DEFAULT TRUE,
    error_msg   VARCHAR(512),
    duration_ms INT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE sys_audit_log IS '审计日志只增不改，应用层不提供删除接口';
COMMENT ON COLUMN sys_audit_log.detail IS 'JSONB，禁止写入密码/令牌等敏感字段';

CREATE INDEX IF NOT EXISTS idx_sys_audit_created ON sys_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_audit_user ON sys_audit_log (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_audit_action ON sys_audit_log (action, created_at DESC);

-- =====================================================================
-- 种子数据：三个内置角色
-- =====================================================================
INSERT INTO sys_role (code, name, description, builtin)
VALUES ('ADMIN', '团队管理员', '系统配置、用户权限、质量看板与门禁策略', TRUE),
       ('REVIEWER', '审查者', '复核机器审查结果、裁决误报、修复验收', TRUE),
       ('DEVELOPER', '开发者', '接入仓库、发起审查、查看报告、整改问题', TRUE)
ON CONFLICT (code) WHERE deleted = 0 DO NOTHING;

-- =====================================================================
-- 种子数据：菜单（一级）
-- =====================================================================
INSERT INTO sys_permission (code, name, type, parent_id, path, icon, sort_order)
VALUES ('menu:dashboard', '质量看板', 'MENU', NULL, '/dashboard', 'DataBoard', 10),
       ('menu:project', '项目管理', 'MENU', NULL, '/projects', 'Folder', 20),
       ('menu:task', '审查任务', 'MENU', NULL, '/tasks', 'Tickets', 30),
       ('menu:issue', '问题中心', 'MENU', NULL, '/issues', 'Warning', 40),
       ('menu:lab', '对比实验', 'MENU', NULL, '/lab', 'DataAnalysis', 50),
       ('menu:config', '引擎配置', 'MENU', NULL, '/config', 'Setting', 60),
       ('menu:system', '系统管理', 'MENU', NULL, '/system', 'User', 70)
ON CONFLICT (code) DO NOTHING;

-- 种子数据：菜单（二级）
INSERT INTO sys_permission (code, name, type, parent_id, path, icon, sort_order)
SELECT v.code, v.name, 'MENU', p.id, v.path, v.icon, v.sort_order
FROM (VALUES ('menu:project:list', '仓库接入', '/projects', 'Connection', 10),
             ('menu:task:list', '任务列表', '/tasks', 'List', 10),
             ('menu:issue:list', '问题列表', '/issues', 'Document', 10),
             ('menu:lab:compare', '三方案沙盘', '/lab/compare', 'Histogram', 10),
             ('menu:config:rule', '规则配置', '/config/rules', 'Operation', 10),
             ('menu:config:prompt', '提示词模板', '/config/prompts', 'ChatDotRound', 20),
             ('menu:config:gate', '合并门禁', '/config/gate', 'Lock', 30),
             ('menu:config:kb', '知识库', '/config/kb', 'Collection', 40),
             ('menu:system:user', '用户管理', '/system/users', 'UserFilled', 10),
             ('menu:system:role', '角色权限', '/system/roles', 'Key', 20),
             ('menu:system:audit', '审计日志', '/system/audit', 'DocumentCopy', 30)
     ) AS v(code, name, path, icon, sort_order)
         JOIN sys_permission p ON p.code = CASE
                                               WHEN v.code LIKE 'menu:project:%' THEN 'menu:project'
                                               WHEN v.code LIKE 'menu:task:%' THEN 'menu:task'
                                               WHEN v.code LIKE 'menu:issue:%' THEN 'menu:issue'
                                               WHEN v.code LIKE 'menu:lab:%' THEN 'menu:lab'
                                               WHEN v.code LIKE 'menu:config:%' THEN 'menu:config'
                                               WHEN v.code LIKE 'menu:system:%' THEN 'menu:system'
        END
ON CONFLICT (code) DO NOTHING;

-- =====================================================================
-- 种子数据：接口/按钮权限点
-- =====================================================================
INSERT INTO sys_permission (code, name, type, sort_order)
VALUES ('system:user:list', '查询用户', 'API', 10),
       ('system:user:create', '新增用户', 'BUTTON', 20),
       ('system:user:update', '修改用户', 'BUTTON', 30),
       ('system:user:delete', '删除用户', 'BUTTON', 40),
       ('system:user:reset-password', '重置密码', 'BUTTON', 50),
       ('system:role:list', '查询角色', 'API', 60),
       ('system:role:create', '新增角色', 'BUTTON', 70),
       ('system:role:update', '修改角色', 'BUTTON', 80),
       ('system:role:delete', '删除角色', 'BUTTON', 90),
       ('system:role:assign', '分配角色权限', 'BUTTON', 100),
       ('system:audit:list', '查看审计日志', 'API', 110),
       ('project:repo:list', '查询项目', 'API', 120),
       ('project:repo:create', '接入仓库', 'BUTTON', 130),
       ('project:repo:update', '修改项目', 'BUTTON', 140),
       ('project:repo:delete', '删除项目', 'BUTTON', 150),
       ('project:repo:credential', '管理 Git 凭据', 'BUTTON', 160),
       ('task:create', '发起审查', 'BUTTON', 170),
       ('task:list', '查询任务', 'API', 180),
       ('task:detail', '查看任务详情', 'API', 190),
       ('task:cancel', '取消任务', 'BUTTON', 200),
       ('task:rerun', '重跑任务', 'BUTTON', 210),
       ('issue:list', '查询问题', 'API', 220),
       ('issue:detail', '查看问题详情', 'API', 230),
       ('issue:status', '更新问题状态', 'BUTTON', 240),
       ('issue:assign', '指派问题', 'BUTTON', 250),
       ('issue:verdict', '误报裁决', 'BUTTON', 260),
       ('issue:verdict:any', '裁决任意问题', 'BUTTON', 270),
       ('config:rule:list', '查询规则', 'API', 280),
       ('config:rule:update', '修改规则开关', 'BUTTON', 290),
       ('config:prompt:list', '查询提示词', 'API', 300),
       ('config:prompt:manage', '维护提示词模板', 'BUTTON', 310),
       ('config:engine:update', '修改引擎参数', 'BUTTON', 320),
       ('config:gate:list', '查询门禁策略', 'API', 330),
       ('config:gate:update', '修改门禁策略', 'BUTTON', 340),
       ('config:strategy:manage', '维护审查策略', 'BUTTON', 350),
       ('gate:override', '强制放行门禁', 'BUTTON', 360),
       ('kb:doc:list', '查询知识库文档', 'API', 370),
       ('kb:doc:upload', '上传知识库文档', 'BUTTON', 380),
       ('kb:doc:delete', '删除知识库文档', 'BUTTON', 390),
       ('kb:defect:list', '查询历史缺陷库', 'API', 400),
       ('kb:defect:delete', '删除历史缺陷', 'BUTTON', 410),
       ('stats:dashboard:view', '查看质量看板', 'API', 420),
       ('stats:screen:view', '查看质量大屏', 'API', 430),
       ('lab:compare:run', '执行对比实验', 'BUTTON', 440)
ON CONFLICT (code) DO NOTHING;

-- =====================================================================
-- 角色授权
-- =====================================================================
-- ADMIN：全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- REVIEWER：复核与治理相关
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p ON p.code IN (
                                                'menu:dashboard', 'menu:project', 'menu:task', 'menu:issue',
                                                'menu:config', 'menu:project:list', 'menu:task:list', 'menu:issue:list',
                                                'menu:config:rule', 'menu:config:kb',
                                                'project:repo:list',
                                                'task:list', 'task:detail', 'task:rerun',
                                                'issue:list', 'issue:detail', 'issue:status', 'issue:assign',
                                                'issue:verdict', 'issue:verdict:any',
                                                'config:rule:list', 'kb:doc:list', 'kb:defect:list',
                                                'stats:dashboard:view'
    )
WHERE r.code = 'REVIEWER'
ON CONFLICT DO NOTHING;

-- DEVELOPER：提交与整改相关
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p ON p.code IN (
                                                'menu:dashboard', 'menu:project', 'menu:task', 'menu:issue',
                                                'menu:project:list', 'menu:task:list', 'menu:issue:list',
                                                'project:repo:list', 'project:repo:create', 'project:repo:update',
                                                'task:create', 'task:list', 'task:detail', 'task:cancel',
                                                'issue:list', 'issue:detail', 'issue:status',
                                                'stats:dashboard:view'
    )
WHERE r.code = 'DEVELOPER'
ON CONFLICT DO NOTHING;
