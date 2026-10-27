-- =====================================================================
-- V3  项目域初始化：被审查项目 + Git 凭据
--
-- 设计要点：
--   1) 一个项目自带一份仓库绑定信息（repo_url / repo_branch / credential_id），
--      对应接口 POST /api/projects/{id}/repos 的"绑定 Git 地址"语义；
--      review_task.project_id 直接指向本表，任务无需再关联仓库表
--   2) 凭据密码（Personal Access Token）用 AES-256-GCM 加密后存 secret_cipher，
--      密钥来自环境变量 APP_REPO_SECRET_KEY，不与数据库同机保存
--   3) sync_status 为派生状态：UNBOUND（未绑定）/ CLONING / READY / FAILED，
--      由仓库同步流程维护，不允许前端直接修改
-- =====================================================================

-- ---------------------------------------------------------------- Git 凭据
CREATE TABLE IF NOT EXISTS repo_credential
(
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    username      VARCHAR(128) NOT NULL,
    secret_cipher TEXT         NOT NULL,
    owner_id      BIGINT       NOT NULL REFERENCES sys_user (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON COLUMN repo_credential.secret_cipher IS 'AES-256-GCM 密文（Base64：IV|密文|Tag），禁止存明文令牌';

CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_credential_name ON repo_credential (name) WHERE deleted = 0;

-- ---------------------------------------------------------------- 项目
CREATE TABLE IF NOT EXISTS repo_project
(
    id                  BIGSERIAL    PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    description         VARCHAR(500),
    language            VARCHAR(32)  NOT NULL DEFAULT 'JAVA',
    repo_url            VARCHAR(512),
    repo_branch         VARCHAR(128),
    credential_id       BIGINT       REFERENCES repo_credential (id) ON DELETE SET NULL,
    sync_status         VARCHAR(16)  NOT NULL DEFAULT 'UNBOUND',
    last_synced_at      TIMESTAMPTZ,
    last_commit_id      VARCHAR(64),
    last_commit_message VARCHAR(500),
    last_commit_at      TIMESTAMPTZ,
    last_error          VARCHAR(512),
    owner_id            BIGINT       NOT NULL REFERENCES sys_user (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON COLUMN repo_project.code IS '项目编码，同时作为本地工作目录名，只允许 [a-z0-9_-]';
COMMENT ON COLUMN repo_project.sync_status IS 'UNBOUND / CLONING / READY / FAILED';
COMMENT ON COLUMN repo_project.last_error IS '最近一次同步失败原因，成功后清空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_project_code ON repo_project (code) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_project_url ON repo_project (repo_url)
    WHERE deleted = 0 AND repo_url IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_repo_project_owner ON repo_project (owner_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_repo_project_sync ON repo_project (sync_status) WHERE deleted = 0;

-- ---------------------------------------------------------------- 权限点补充
-- 项目域的接口权限点在 V1 已写入（project:repo:*），此处补充"查看项目详情"，
-- 与列表权限分离：LIST 用于菜单可见性，DETAIL 用于查看单条记录
INSERT INTO sys_permission (code, name, type, sort_order)
VALUES ('project:repo:detail', '查看项目详情', 'API', 155)
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p ON p.code = 'project:repo:detail'
WHERE r.code IN ('ADMIN', 'REVIEWER', 'DEVELOPER')
ON CONFLICT DO NOTHING;
