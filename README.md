# 智能代码审查系统（AI Code Review）

> 基于大语言模型的智能代码审查系统 —— 静态分析 + 大模型语义审查双引擎融合
>
> 南昌应用技术师范学院 · 软件工程 · 2027 届毕业设计

## 一、这是什么

面向研发团队的代码审查平台。针对两类现实痛点：

- **纯静态分析**（SonarQube / PMD）查得准、查得快，但只认规则，报告里大量"命名不规范"级噪音，语义级问题（边界条件、隐藏逻辑缺陷）查不出；
- **纯大模型审查**结论可读性好，但幻觉严重、结论不可溯源、成本高。

本项目的做法是**双引擎融合**：静态分析负责确定性缺陷，大模型负责语义级缺陷，两路结果按「文件 + 行号 + 缺陷类型」归并去重，再用**历史缺陷知识库（RAG）**给模型结论提供可溯源的依据，最后用**合并门禁**把审查结论变成"能不能放行"的硬约束。

设计文档见 [`docs/01-项目蓝皮书.md`](docs/01-项目蓝皮书.md)。

## 二、技术栈

| 层次 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · Spring Security（JWT）· MyBatis-Plus · Flyway |
| 数据库 | PostgreSQL 16 + pgvector（向量检索）· Redis 7（队列 / 令牌 / 缓存） |
| 静态分析 | PMD 7（源码级 AST，主引擎）· SpotBugs 4.8（字节码级，可选） |
| 代码解析 | JavaParser · JGit |
| 大模型 | DeepSeek（OpenAI 兼容协议） |
| 向量化 | BGE-M3（本地部署，1024 维，L2 归一化） |
| 对象存储 | MinIO |
| 前端 | Vue 3.5 · TypeScript · Vite 6 · Element Plus · Pinia · Monaco Editor · ECharts |

## 三、目录结构

```
AI-CodeReview/
├─ docs/                 设计文档（蓝皮书等）
├─ backend/              Spring Boot 服务
│  └─ src/main/java/com/shenchao/codereview/
│     ├─ common/         统一响应、错误码、异常、工具
│     ├─ config/         Security / MyBatis-Plus / 线程池 / OpenAPI
│     ├─ auth/           登录、JWT、刷新令牌、权限上下文
│     └─ system/         用户、角色、权限、审计
├─ frontend/             Vue3 前端
├─ embedding/            BGE-M3 向量化服务（FastAPI，备选方案）
├─ deploy/               docker-compose、环境变量模板、Nginx
└─ scripts/              演示数据与实验脚本
```

## 四、环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | `java -version` 校验 |
| Maven | 3.9+ | |
| Node.js | 18+（推荐 20/22） | |
| Docker Desktop | 最新版 | **必须**。PostgreSQL 需要 pgvector 扩展，Windows 下无法用安装包装 |

> ⚠️ 当前开发机尚未安装 Docker Desktop，这是启动项目的前置条件。安装后需在设置中开启 WSL2 后端。

## 五、快速启动

### 1. 启动中间件

```bash
cd deploy
cp .env.example .env          # 按需修改；.env 已在 .gitignore 中，不会被提交
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps      # 四个服务都应为 healthy
```

首次启动 `embedding` 需下载约 2.2GB 的 BGE-M3 模型，耗时取决于网络。
模型缓存在 `tei-cache` 卷中，后续重启不再下载。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

- 启动时 Flyway 自动建表并写入角色、权限种子数据
- 首次启动自动创建管理员：`admin` / `Admin@123456`（**登录后立即修改**）
- 接口文档：http://localhost:8080/api/swagger-ui.html
- 健康检查：http://localhost:8080/api/actuator/health

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173 ，使用 `admin` / `Admin@123456` 登录。

> 前端统一请求 `/api/**`，由 Vite 代理转发到 `localhost:8080`，浏览器视角同源，
> 因此 HttpOnly 刷新令牌 Cookie 无需处理跨域凭证问题。

## 六、开发规范（本项目强制遵守）

### 6.1 接口层

- 所有接口返回 `Result<T>`：`{ code, message, data, traceId, timestamp }`，`code = 0` 表示成功
- 业务错误一律 `throw new BizException(ErrorCode.X)`，由 `GlobalExceptionHandler` 统一翻译
- 禁止把实体直接返回前端；用户类数据必须转 VO（否则会泄漏密码摘要）
- 分页参数继承 `PageQuery`，页大小上限 100，由拦截器二次兜底

### 6.2 错误码分段

| 段位 | 含义 |
|---|---|
| `0` | 成功 |
| `40xxx` | 客户端错误（参数、格式） |
| `401xx` | 未认证 / 凭证失效 |
| `403xx` | 已认证但无权限 |
| `404xx` | 资源不存在 |
| `409xx` | 冲突（重复提交、状态不允许） |
| `429xx` | 限流 / 账号锁定 |
| `50xxx` | 服务端错误 |
| `60xxx` | 领域错误（引擎、知识库、门禁） |

### 6.3 权限

- 功能入口用 `@PreAuthorize("hasAuthority('task:create')")`，按**权限点**而非角色鉴权
- 数据级越权（开发者 A 查开发者 B 的任务）用 `SecurityUtils.requireSelfOrPermission(ownerId, code)` 兜底
- 前端 `hasPermission()` 只用于隐藏按钮，**不是安全边界**

### 6.4 审计

- 需要留痕的写操作加 `@Audit(action = "XXX", resource = "YYY")`
- 入参自动脱敏（password / token / secret 等字段替换为 `***`）
- 审计走独立线程池与独立事务，业务回滚也要留痕

### 6.5 异常与边界

- 所有集合、`Optional`、外部响应字段在使用前判空
- 所有外部调用设超时；LLM 调用超时 60s、重试 1 次
- 外部输入（Git URL、上传包、知识库文档）一律做长度/大小/类型校验
- 异步任务必须捕获异常并落库，禁止静默失败

## 七、里程碑进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 环境与骨架、Flyway 建库、JWT 登录链路 | ✅ 已完成 |
| M1 | 用户/角色/权限管理、项目与仓库接入 | ⬜ 待开发 |
| M2 | 任务状态机 + Redis 队列 + PMD 引擎 + 引擎 SPI | ⬜ |
| M3 | 增量切片 + LLM 语义引擎 + 结构化输出 | ⬜ |
| M4 | 融合算法 + 报告接口 + 报告页 | ⬜ |
| M5 | RAG 知识库 + 缺陷库 + 溯源展示 | ⬜ |
| M6 | 问题闭环 + 裁决 + 统计看板 + 合并门禁 | ⬜ |
| M6.5 | 深度增强六项（E1–E6） | ⬜ |
| M6.6 | 暗色主题 + 轻量大屏 | ⬜ |
| M7 | 测试 + 对比实验 + Docker 部署 | ⬜ |

## 八、常见问题

**Q：`docker compose up` 报端口被占用**
A：本机 5432/6379 可能已有 PostgreSQL/Redis。改 `deploy/.env` 中 `DB_PORT`、`REDIS_PORT` 即可。

**Q：后端启动报 `Extension "vector" is not available`**
A：用错了镜像。必须使用 `pgvector/pgvector:pg16`，官方 `postgres` 镜像不含 pgvector。

**Q：登录返回 503**
A：认证链路依赖 Redis 做令牌黑名单，Redis 不可用时系统按 fail-closed 策略拒绝请求。检查 Redis 容器状态。

**Q：向量维度不匹配（expected 1024 dimensions）**
A：`EMBEDDING_DIM` 与数据库 `vector(1024)` 必须一致。换模型时需同步改表结构并重建 HNSW 索引。

## 九、许可证

MIT
