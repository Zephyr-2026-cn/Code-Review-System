-- =====================================================================
-- V2  用户表补充：强制改密标记
--
-- 背景：本项目不开放自助注册，账号一律由管理员创建，初始密码由系统随机生成。
-- 管理员把初始密码转交用户后，用户首次登录必须修改密码，否则管理员
-- 始终掌握着该账号的可用凭证，不满足"凭证唯一归属"的基本要求。
--
-- 该标记在登录成功后随 /auth/me 返回，前端据此强制跳转改密页；
-- 同时管理员"重置密码"也会重新置为 TRUE。
-- =====================================================================

ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS password_reset_required BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sys_user.password_reset_required IS '为 TRUE 时用户登录后必须修改密码（管理员创建/重置密码后置位）';

-- 存量账号（含 bootstrap 创建的 admin）视为已知密码，不强制改密
UPDATE sys_user SET password_reset_required = FALSE WHERE password_reset_required IS NULL;
