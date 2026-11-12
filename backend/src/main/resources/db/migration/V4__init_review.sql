-- =====================================================================
-- V4  审查任务域：任务 / 任务文件 / 缺陷 / 规则开关 / 规则类别映射
-- 设计要点：
--   1) review_task 保留完整状态机字段与 fail_stage，前端能显示"卡在哪一步"
--   2) 同一项目 + 同一提交只允许存在一个进行中的任务（部分唯一索引兜底），
--      避免用户双击或接口重试导致同一份代码被并发审查两遍
--   3) review_issue 用 fingerprint 做任务内去重，静态/LLM 双引擎融合后不会重复计数
--   4) 规则类别映射独立成表，答辩时可直接展示"两套体系如何对齐"
-- =====================================================================

-- ---------------------------------------------------------------- 审查任务
CREATE TABLE IF NOT EXISTS review_task
(
    id               BIGSERIAL    PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES repo_project (id),
    trigger_type     VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    base_commit      VARCHAR(64),
    head_commit      VARCHAR(64)  NOT NULL,
    review_mode      VARCHAR(16)  NOT NULL DEFAULT 'FULL',
    status           VARCHAR(24)  NOT NULL DEFAULT 'CREATED',
    fail_stage       VARCHAR(24),
    fail_reason      VARCHAR(1000),
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    engine_flags     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    stats_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    issue_count      INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NOT NULL,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN review_task.status IS 'CREATED/QUEUED/FETCHING/SLICING/ANALYZING/FUSING/DONE/PARTIAL/FAILED/CANCELED';
COMMENT ON COLUMN review_task.review_mode IS 'FULL 全量扫描 / INCREMENTAL 增量（基于 base_commit..head_commit）';
COMMENT ON COLUMN review_task.engine_flags IS '{"pmd":true,"spotbugs":false,"llm":false,"rag":false}';
COMMENT ON COLUMN review_task.stats_json IS '各阶段耗时、文件数、缺陷数等快照，供报告页与答辩演示';

CREATE INDEX IF NOT EXISTS idx_task_project ON review_task (project_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_task_status ON review_task (status);
CREATE INDEX IF NOT EXISTS idx_task_created_at ON review_task (created_at DESC);

-- 同一项目同一提交只允许一个进行中的任务：
-- 状态在 CREATED..FUSING 之间时视为"在途"，终态记录不参与唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_active
    ON review_task (project_id, head_commit)
    WHERE status IN ('CREATED', 'QUEUED', 'FETCHING', 'SLICING', 'ANALYZING', 'FUSING');

-- ---------------------------------------------------------------- 任务文件
CREATE TABLE IF NOT EXISTS review_task_file
(
    id           BIGSERIAL    PRIMARY KEY,
    task_id      BIGINT       NOT NULL REFERENCES review_task (id) ON DELETE CASCADE,
    file_path    VARCHAR(512) NOT NULL,
    change_type  VARCHAR(16)  NOT NULL DEFAULT 'SCANNED',
    language     VARCHAR(16)  NOT NULL DEFAULT 'JAVA',
    additions    INT          NOT NULL DEFAULT 0,
    deletions    INT          NOT NULL DEFAULT 0,
    analyzed     BOOLEAN      NOT NULL DEFAULT FALSE,
    skip_reason  VARCHAR(255),
    defect_count INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_file ON review_task_file (task_id, file_path);

-- ---------------------------------------------------------------- 缺陷
CREATE TABLE IF NOT EXISTS review_issue
(
    id          BIGSERIAL    PRIMARY KEY,
    task_id     BIGINT       NOT NULL REFERENCES review_task (id) ON DELETE CASCADE,
    project_id  BIGINT       NOT NULL,
    file_path   VARCHAR(512) NOT NULL,
    start_line  INT          NOT NULL,
    end_line    INT          NOT NULL,
    category    VARCHAR(32)  NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    title       VARCHAR(256) NOT NULL,
    description TEXT,
    suggestion  TEXT,
    fix_snippet TEXT,
    source      VARCHAR(16)  NOT NULL,
    rule_id     VARCHAR(128),
    confidence  NUMERIC(4, 3),
    flag        VARCHAR(24),
    status      VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    evidence    JSONB,
    fingerprint VARCHAR(64)  NOT NULL,
    assigned_to BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN review_issue.category IS 'NULL_POINTER/RESOURCE_LEAK/CONCURRENCY/INJECTION/PERFORMANCE/MAINTAINABILITY/LOGIC_ERROR/CODE_STYLE';
COMMENT ON COLUMN review_issue.source IS 'STATIC/LLM/FUSED';
COMMENT ON COLUMN review_issue.flag IS 'CROSS_VALIDATED/STATIC_ONLY/LLM_ONLY/LLM_RAG/CONFLICT';
COMMENT ON COLUMN review_issue.fingerprint IS '文件+规则+行+类别的 SHA-256，任务内去重';

CREATE INDEX IF NOT EXISTS idx_issue_task ON review_issue (task_id, severity);
CREATE INDEX IF NOT EXISTS idx_issue_status ON review_issue (project_id, status);
CREATE INDEX IF NOT EXISTS idx_issue_file ON review_issue (task_id, file_path);
CREATE UNIQUE INDEX IF NOT EXISTS uk_issue_fingerprint ON review_issue (task_id, fingerprint);

-- ---------------------------------------------------------------- 规则开关
CREATE TABLE IF NOT EXISTS rule_config
(
    id                BIGSERIAL    PRIMARY KEY,
    rule_id           VARCHAR(128) NOT NULL,
    ruleset           VARCHAR(128),
    name              VARCHAR(128),
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    severity_override VARCHAR(16),
    weight            NUMERIC(3, 2) NOT NULL DEFAULT 1.0,
    params            JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_config ON rule_config (rule_id);

-- ---------------------------------------------------------------- 规则 → 统一类别映射
CREATE TABLE IF NOT EXISTS rule_category_map
(
    rule_id           VARCHAR(128) PRIMARY KEY,
    pmd_category      VARCHAR(32)  NOT NULL,
    unified_category  VARCHAR(32)  NOT NULL,
    default_severity  VARCHAR(16)  NOT NULL,
    note              VARCHAR(255)
);

COMMENT ON TABLE rule_category_map IS 'PMD 规则与统一缺陷类别的对齐表，是双引擎融合的前提（蓝皮书 6.1）';

INSERT INTO rule_category_map (rule_id, pmd_category, unified_category, default_severity, note)
VALUES ('CloseResource', 'errorprone', 'RESOURCE_LEAK', 'CRITICAL', '流/连接未关闭'),
       ('UseTryWithResources', 'bestpractices', 'RESOURCE_LEAK', 'MAJOR', '可用 try-with-resources 自动释放'),
       ('BrokenNullCheck', 'errorprone', 'NULL_POINTER', 'CRITICAL', '空值判断本身写错，判断后仍可能为空'),
       ('NullAssignment', 'errorprone', 'NULL_POINTER', 'MAJOR', '把 null 赋给变量'),
       ('UnusedNullCheckInEquals', 'errorprone', 'NULL_POINTER', 'MAJOR', 'equals 前的空判断无效'),
       ('LiteralsFirstInComparisons', 'bestpractices', 'NULL_POINTER', 'MAJOR', '字面量应放 equals 左侧'),
       ('EmptyCatchBlock', 'errorprone', 'LOGIC_ERROR', 'MAJOR', '空 catch 吞掉异常'),
       ('AvoidCatchingThrowable', 'errorprone', 'LOGIC_ERROR', 'MAJOR', '捕获 Throwable 会吞掉 Error'),
       ('AvoidCatchingNPE', 'errorprone', 'LOGIC_ERROR', 'MAJOR', '用 catch NPE 代替判空'),
       ('CompareObjectsWithEquals', 'errorprone', 'LOGIC_ERROR', 'MAJOR', '对象用 == 比较'),
       ('EqualsNull', 'errorprone', 'LOGIC_ERROR', 'MINOR', 'equals(null) 恒为 false'),
       ('ReturnFromFinallyBlock', 'errorprone', 'LOGIC_ERROR', 'CRITICAL', 'finally 中 return 会吞异常'),
       ('UseEqualsToCompareStrings', 'errorprone', 'LOGIC_ERROR', 'CRITICAL', '字符串用 == 比较'),
       ('SimpleDateFormatNeedsLocale', 'errorprone', 'LOGIC_ERROR', 'MINOR', '未指定 Locale'),
       ('DoNotTerminateVM', 'errorprone', 'LOGIC_ERROR', 'CRITICAL', '直接终止 JVM'),
       ('PreserveStackTrace', 'bestpractices', 'LOGIC_ERROR', 'MAJOR', '重新抛异常时丢失原始栈'),
       ('SwitchStmtsShouldHaveDefault', 'bestpractices', 'LOGIC_ERROR', 'MINOR', 'switch 缺少 default'),
       ('HardCodedCryptoKey', 'security', 'INJECTION', 'BLOCKER', '硬编码密钥，等同于密钥泄露'),
       ('InsecureCryptoIv', 'security', 'INJECTION', 'CRITICAL', '固定 IV 破坏加密语义'),
       ('DoubleCheckedLocking', 'multithreading', 'CONCURRENCY', 'CRITICAL', '双重检查锁在旧内存模型下不安全'),
       ('UnsynchronizedStaticFormatter', 'multithreading', 'CONCURRENCY', 'MAJOR', '共享 SimpleDateFormat 非线程安全'),
       ('AvoidUsingVolatile', 'multithreading', 'CONCURRENCY', 'MAJOR', 'volatile 不保证复合操作原子性'),
       ('UseNotifyAllInsteadOfNotify', 'multithreading', 'CONCURRENCY', 'MAJOR', 'notify 可能唤醒错误线程'),
       ('AvoidInstantiatingObjectsInLoops', 'performance', 'PERFORMANCE', 'MINOR', '循环内重复创建对象'),
       ('ConsecutiveLiteralAppends', 'performance', 'PERFORMANCE', 'MINOR', '连续追加字面量应合并'),
       ('StringInstantiation', 'performance', 'PERFORMANCE', 'MINOR', 'new String 多余'),
       ('StringToString', 'performance', 'PERFORMANCE', 'MINOR', '对字符串调用 toString'),
       ('UseStringBufferForStringAppends', 'performance', 'PERFORMANCE', 'MINOR', '循环内字符串拼接'),
       ('AddEmptyString', 'performance', 'PERFORMANCE', 'INFO', '用 +"" 转字符串'),
       ('InefficientEmptyStringCheck', 'performance', 'PERFORMANCE', 'MINOR', 'trim().length() 判空'),
       ('GuardLogStatement', 'bestpractices', 'PERFORMANCE', 'MINOR', '日志参数无谓求值'),
       ('AvoidPrintStackTrace', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '应使用日志框架'),
       ('SystemPrintln', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '生产代码不应直接打印'),
       ('UnusedLocalVariable', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '局部变量未使用'),
       ('UnusedPrivateField', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '私有字段未使用'),
       ('UnusedPrivateMethod', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '私有方法未使用'),
       ('UnusedFormalParameter', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '方法参数未使用'),
       ('MissingOverride', 'bestpractices', 'MAINTAINABILITY', 'INFO', '重写方法缺 @Override'),
       ('UseStandardCharsets', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '字符集应用 StandardCharsets'),
       ('LooseCoupling', 'bestpractices', 'MAINTAINABILITY', 'INFO', '应面向接口而非实现编程'),
       ('AvoidReassigningParameters', 'bestpractices', 'MAINTAINABILITY', 'MINOR', '修改入参易产生副作用'),
       ('AvoidDeeplyNestedIfStmts', 'design', 'MAINTAINABILITY', 'MAJOR', '嵌套过深，可读性差'),
       ('CollapsibleIfStatements', 'design', 'MAINTAINABILITY', 'MINOR', '可合并的 if'),
       ('SimplifiedTernary', 'design', 'MAINTAINABILITY', 'MINOR', '三元表达式可简化'),
       ('CyclomaticComplexity', 'design', 'MAINTAINABILITY', 'MAJOR', '圈复杂度超阈值'),
       ('CognitiveComplexity', 'design', 'MAINTAINABILITY', 'MAJOR', '认知复杂度超阈值'),
       ('ExcessiveParameterList', 'design', 'MAINTAINABILITY', 'MINOR', '参数过多，考虑封装'),
       ('UselessOverridingMethod', 'design', 'MAINTAINABILITY', 'MINOR', '仅调用 super 的重写无意义'),
       ('UncommentedEmptyMethodBody', 'documentation', 'MAINTAINABILITY', 'INFO', '空方法体缺注释'),
       ('MissingSerialVersionUID', 'errorprone', 'MAINTAINABILITY', 'MINOR', '序列化类缺 serialVersionUID'),
       ('AvoidLiteralsInIfCondition', 'errorprone', 'MAINTAINABILITY', 'MINOR', '条件中的魔法值'),
       ('UselessParentheses', 'codestyle', 'CODE_STYLE', 'INFO', '多余括号'),
       ('UnnecessaryImport', 'codestyle', 'CODE_STYLE', 'INFO', '多余 import'),
       ('UnnecessaryModifier', 'codestyle', 'CODE_STYLE', 'INFO', '多余修饰符'),
       ('ExtendsObject', 'codestyle', 'CODE_STYLE', 'INFO', '显式继承 Object'),
       ('UnnecessaryConstructor', 'codestyle', 'CODE_STYLE', 'INFO', '多余构造函数')
ON CONFLICT (rule_id) DO NOTHING;
