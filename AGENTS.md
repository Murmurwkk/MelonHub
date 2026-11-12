# MelonHub 开发规范与任务交接

最后更新：2026-10-19（Asia/Shanghai）。当前工作目录：`D:\MelonHub`。本地 Git 仓库的功能分支为 `rbac-platform`；既有改动按运行环境、控制台、鉴权、缓存限流、RBAC 授权、授权治理、Webhook、审计监控、Java SDK 和文档拆分提交。`origin` 指向个人 fork `https://github.com/xqy2006/MelonHub`，`upstream` 指向原仓库 `https://github.com/JiuQi79-BOT/MelonHub`；后续改动推送到个人 fork，通过 PR 向上游贡献。

上一轮完成“企业化改造”：修复鉴权绕过与监听地址缺陷，新增 Redis 权限缓存与无锁校验、限流与过载保护、多级管理员、多 API Key、审计增强、权限诊断、角色成员、跨部门复制，并用 Ant Design Vue 4 重建控制台。

本轮（V3）完成授权治理与生态：角色分配有效期与自动到期、角色申请与审批、定期权限复核、Webhook 变更通知（事务外盒、签名、重试）、用户批量导入与权限清单导出导入、权限反查与即将到期报表、管理员 TOTP 两步验证、审计 SHA-256 哈希链、经 Redis 的跨实例全局限流与按软件限额、删除部门/软件时的依赖提示、Java SDK（`sdk/java`），以及控制台克制风格改版（明暗主题、全局搜索、14 个页面）。

2026-10-19 按三批复审意见（9 + 20 + 5 项）修复：上下文创建可拒绝切换部门（`DEPARTMENT_MISMATCH`）、逐角色到期与 `grant_id`、复核只回收快照中的那次分配、治理历史名称快照、待审批唯一、批准可永久、Webhook 并行投递与按尝试次数认领、审计链按封存顺序与锚点及 v2 规范化、管理员会话绑定凭据版本、72 字节密码上限、角色成员替换的锁顺序与重试、系统状态降级时字段固定，以及对应的控制台（过时响应丢弃、抽屉状态重置、空值显示）与 SDK 改动。详细结果见 `doc/验证记录.md` 第 1 节。

## 1. 用户要求与边界

2026-10-19 最新补修：全量审查保留的 15 项问题已作最小修复；用户授权新增跨页选人、多角色批量追加与同页个别调整，保留已有有效分配及期限。普通列表 CSV、软件状态筛选、角色全选/清空恢复。MFA 变更结束旧会话、完整响应超时、导出快照、Webhook 数组契约及通知、旧 401 竞态等修复见 `doc/验证记录.md` 第 1.0 节；未增加迁移。用户要求最小修改，不添加无关防御性分支或抽象。

随后按用户要求优化对象内配置流程：选定部门/软件后打开配置抽屉，角色/权限/成员授权复用原页面内容，移除三个独立侧栏入口并保留旧地址。新建部门→维护成员，接入软件→保存密钥→配置角色，新建角色→创建权限并勾选→保存并分配成员均连续完成。多部门复制、多软件配置、多软件批量授权组合现有 API，各项独立提交并保留成功结果；不增加部门与软件准入关系或模板同步。仅修改前端与说明文档，后端和迁移未动。

手机完整流程复验补修：仅改 4 个前端文件。手机日期/时间及双月份面板纵向排列、固定在屏幕内且可滚动；权限元数据列改“动作”、审计列改“事件”，避免被手机样式误当成“操作”按钮列；诊断的跨部门警告只在已有其他当前部门时显示。详情见 `doc/验证记录.md` 第 1.0.2 节。

- 用户定位：公司内部统一权限平台，为 20+ 业务软件通过 API 提供 RBAC 授权；隔离域为 部门 × 软件；切换部门使旧上下文失效。
- 用户确认的方向：企业级 RBAC 授权平台（**不做** OAuth/OIDC/IdP/SSO），前端 Ant Design Vue 4 的高密度表格控制台，高峰用 Redis 权限缓存并保证失效正确。
- 说明文档统一中文，放在 `doc/`，README 链接；保留一键启动和一键停止脚本；IDEA 可维护运行，但图形界面启动未实测，不能描述为已验证。
- 当前复用本机 Docker 的已有 MySQL `xmudemo-mysql`（8.3.0，卷 `xmudemo_db_data`），只使用 `melonhub_rbac` 和 `melonhub_rbac_test`。不得修改其他应用的数据库、数据或容器，不重建 Swarm、不删除持久化目录、不执行实验初始化 SQL。名为 backend 的 Compose 项目是 PostgreSQL，与本项目无关。
- Redis 只用本项目专用容器 `melonhub-redis`（标签 `com.melonhub.role=cache`，`127.0.0.1:6380`）。Docker 中其他 Redis 不得使用或修改；脚本对没有该标签的同名容器拒绝操作。
- 真实地址、数据库/SSH/Redis/管理员密码只在根目录忽略文件 `.env`，`.local/redis/redis.conf` 也含 Redis 密码。不要打印原文，不写进示例、文档、日志或命令行参数。Webhook 签名密钥和 TOTP 密钥以原文存在数据库中，同样不得打印或写入文档。
- 中文沟通，直接推进；只读诊断和常规修复不反复询问。修改前先阅读当前文件和相关代码，尊重用户已有编辑。

## 2. 工程与目录

| 项目 | 当前情况 |
| --- | --- |
| 后端 | Spring Boot 3.5.7、Spring Data JPA（Hibernate；迁移进行中：`AuthDao`、`AdminRoleDao` 已用 JPA，其余 DAO 仍为 JdbcTemplate）、Spring Data Redis（Lettuce）、Bean Validation、Flyway、MySQL Connector/J、Actuator + Micrometer Prometheus、spring-security-crypto |
| Java | 源码目标 Java 17；当前使用 `C:\Program Files\Java\jdk-17` |
| 前端 | Vue 3.5.22、TypeScript 5.9.3、Pinia 3.0.3、Vue Router 4.5.1（hash 路由）、Vite 6.4.1、ant-design-vue 4.2.6、@ant-design/icons-vue 7、dayjs |
| Java SDK | `sdk/java`，`com.melonhub:melonhub-client:1.0.0`，独立 Maven 工程（不在根 `pom.xml` 聚合中），基于 JDK HttpClient，Spring Boot 自动装配可选；未发布到远程仓库，需 `mvn -f sdk/java/pom.xml install` |
| Maven | 根 `pom.xml` 聚合 `backend`，后端 `backend/pom.xml`；启动器可发现 PATH、MAVEN_HOME/M2_HOME 或 `.m2/wrapper/dists` |
| IDEA | `C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.1`；图形启动未实测 |
| Shell | PowerShell 7；优先 rg 搜索。PowerShell 别名会遮蔽同名函数（例如 `Del`），临时脚本的函数名要避开内置别名 |

后端包 `com.melonhub.rbac`，根包只保留 `RbacApplication`（启用定时任务）：

| 包 | 文件与职责 |
| --- | --- |
| controller | `AuthController` 健康/登录/改密/两步验证/me，`AdminController` 控制台基础配置，`GovernanceController`（`/api/admin`）审批、复核、Webhook、清单、用户导入、权限反查、即将到期、软件限额、搜索、系统状态、审计链校验，`AccountController` 管理员账号（含 MFA 重置、解锁），`BusinessController` 业务 API（含角色申请与只读授权查询） |
| service | `RbacService` 配置与规则，`ContextService` 上下文与校验，`AuthService` 管理员、TOTP 与 API Key 认证，`GovernanceService` 审批/复核/导入/清单/反查，`WebhookService` 事件外盒与投递，`AuditSealService` 审计哈希链封存与校验，`ConsoleService` 搜索与状态看板，`MaintenanceService` 定时清理与分配到期 |
| dao | `RbacDao`、`ContextDao`、`GovernanceDao`、`WebhookDao`、`AuditDao`：JdbcTemplate、SQL、数据库锁；`AuthDao`、`AdminRoleDao`：JPA（`entity` 实体、`repository` Spring Data 仓库），不写原生 SQL。DAO 方法签名与返回结构保持不变，写操作在方法返回前 flush；做决定的读取用 JPQL 标量投影（不受持久化上下文中旧实体影响），加锁用 `@Lock`/`refresh(…, LockModeType)`，时间取应用服务器时钟 |
| cache | `PermissionCache`：Redis 缓存、代标记失效协议、熔断与纪元恢复 |
| dto | `Requests`：输入类型与校验 |
| security | `ApiGuard` 统一鉴权/限流/请求编号/安全头，`GlobalRateLimiter` Redis 固定窗口跨实例限流（失败回退 `RateLimiter`），`ApplicationRateLimits` 软件限额（实例缓存 30 秒），`Totp`，`AdminAccess`/`AdminPrincipal` 管理员权限，`ApplicationCaller`，`RateLimiter`，`ClientAddress`，`Credentials` |
| exception | `ApiException`、`ApiErrors`：结构化错误（含 requestId） |
| config | `CloudDatabaseConfiguration`（保留历史类名）、`RedisConfiguration`、`DemoData` |

迁移：`V1__rbac.sql`、`V2__enterprise.sql`、`V3__governance.sql`（`user_roles` 到期/授予人、`applications.rate_limit`、`access_requests`、`access_reviews`、`access_review_items`、`webhooks`、`webhook_deliveries`、`admin_accounts` MFA 字段、`audit_events.prev_hash/chain_hash`、`audit_seal`）、`V4__audit_seal_order.sql`（`audit_events.seal_seq`，`audit_seal` 的 `last_seq`、`anchor_seq/anchor_hash`）、`V6__admin_functions.sql`（`admin_functions` 功能清单、`admin_roles`、`admin_role_functions`、`admin_accounts.all_applications` 与角色外键），`V7__admin_tree.sql`（`admin_accounts.parent_username/kind/all_departments`、`admin_account_departments`，原非超级管理员账号挂到首个超级管理员之下），`V5__governance_history.sql`（`admin_accounts/admin_sessions.credential_version`、`user_roles.grant_id`、申请与复核条目的名称快照且外键改为 `ON DELETE SET NULL`、`access_requests.pending_role_id` 唯一索引、复核条目 `grant_id/skipped`、`audit_seal.v2_from_seq`）。已应用的迁移不得修改，结构变更新建 V6。

Controller、Service 不直接使用 JdbcTemplate，不把 SQL 搬回服务层。不新增无意义抽象。

前端 `frontend/src`：`main.ts` 路由与权限守卫（`superOnly` 页面仅超级管理员；`domain` 标记的页面显示授权域选择），`App.vue` 布局（侧栏、面包屑、全局搜索、授权域选择、健康徽标、主题切换、账号菜单含两步验证与修改密码；手机上侧栏改为左侧抽屉、授权域移到顶栏下方整行、全局搜索收成图标），`store.ts` 身份与授权域，`api.ts` 请求与会话（token 在 sessionStorage），`theme.ts` 明暗主题与 antd 主题令牌（默认跟随系统，选择存 localStorage `melonhub-theme`，颜色需与 `style.css` 变量同步），`responsive.ts` 手机断点（≤768px，与 `style.css` 的媒体查询同步）：`isPhone`，以及给表格单元格写 `data-label`（列标题）和 `data-empty`（只有“—”或空）供手机卡片布局使用，“操作”列按标题识别，`composables.ts` 带请求序号的分页/列表（丢弃过时响应），`labels.ts` 中文标签，`types.ts` 类型。`pages/` 14 个页面：Overview、Departments、Users（含批量导入）、Applications（API Key/Webhook 抽屉，“更多”中限流）、Roles（“清单与复制”下拉：导出本部门清单/导出全部部门清单/导入清单/从其他部门复制）、Permissions（使用者反查）、Assignments（逐角色到期、统一有效天数、即将到期标签）、Approvals、Reviews、Simulate、Audit（校验链条）、Status、Accounts、Integration。`components/`：DomainPicker、DomainEmpty、GlobalSearch（Ctrl/⌘+K）、ThemeToggle、PageHead、EmptyState、ReadonlyHint、LoginView（MFA 口令步骤）、MfaModal、PasswordModal、KeysDrawer、WebhooksDrawer、ReviewDrawer、PermissionHoldersDrawer、UserAccessDrawer、RoleMembersDrawer、UserPicker、SecretModal。样式在 `style.css`，覆盖 antd 默认样式时要提高选择器优先级（如 `.shell .ant-layout-header.header`），深色模式需同时检查。

对象内配置新增：`authorizationScope.ts` 提供局部授权域，默认回退全局 store；`AuthorizationDrawer` 内复用 Roles/Permissions/Assignments/Users，Users 总表异步引用该抽屉以避免模块循环初始化。`GuardedButton`/`GuardedLink` 用于需要管理功能的按钮与操作链接：无权限时置灰并以悬停提示说明缺少的功能（`hub.denial`），不要再用 `v-if` 隐藏；`PermissionEditor` 是原权限表单的共用实现；`DomainCopyModal` 逐目标调用现有复制接口；`BatchRolesModal` 逐软件调用现有批量追加接口。抽屉内切换软件保留本部门选人，切换部门清空；已有配置、有效分配与期限保留。各页面的旧 URL 仍可使用。

文档：`doc/使用说明书.md`、`API.md`、`ARCHITECTURE.md`、`本地Docker运行.md`、`验证记录.md`；SDK 文档 `sdk/java/README.md`。

## 3. 核心权限模型

RBAC0，隔离域 `(department_id, application_id)`。用户与角色、角色与权限多对多，没有用户直接权限；不引入角色继承、ABAC、职责分离、通配权限或业务规则。权限编码区分大小写，resource/action 仅为元数据。角色分配可带 `expires_at`，所有查询只计入未到期分配；到期是分配的属性，不是新规则。

**用户全局当前部门**，跨软件、跨设备共享。在另一部门创建上下文使旧部门全部软件上下文失效，切回不恢复。设备独立切换属于需求变更。

- `ContextService.issue()` 对用户行 `SELECT ... FOR UPDATE`，验证成员、用户/软件启用及激活角色为已分配角色子集；省略 activeRoleIds 为全部，空数组为无。跨部门切换增加 context_version、更新 active_department_id、清理旧版本。`switchDepartment:false` 时用户已有其他当前部门则返回 409 `DEPARTMENT_MISMATCH`，不切换；SDK 的 `hasPermission`/`token` 都这样调用，只有 `switchDepartment` 方法切换。
- 校验（`effective`/`check`）**不加锁**：先查 Redis；未命中则在只读事务中读一致性快照（`ContextDao.state`）。语义：变更提交后开始的校验一定看到新状态，与提交并发的校验可能返回旧结果。
- 失效：修改外部标识、移出当前部门、停用/删除用户、强制下线 → 上下文失效；用户在某域角色重分配、角色成员调整、批准申请、复核关闭回收 → 该域上下文失效；软件停用/删除 → 清除上下文；删除部门 → 清除前成员遗留上下文；角色停用、权限调整、清单导入 → 下一次校验生效；分配到期 → 不失效上下文，到期角色从到期时刻起不再计入（缓存结果带 `refreshAt`，见下）。
- 删除部门/软件有依赖时返回 409 并在 message 中列出剩余数量（`RbacService.remaining`）；审计对象为名称，详情“编码 xxx”。
- 写路径行锁顺序统一为“用户 → 角色 → 角色分配”。`RbacService.roleMembers` 先按 ID 顺序锁全部相关用户，再锁角色与分配；加锁读取若发现等锁期间新加入的成员，不在持有角色锁时再去锁该用户，而是回滚本次尝试，下一次把这些用户放在最前面，最多 3 次，仍冲突返回 503 `SERVICE_BUSY`。每次尝试用 `PROPAGATION_REQUIRED`（JPA 不支持保存点）：从控制器调用时没有外层事务，每次尝试是独立事务，回滚即释放全部锁；在调用方事务内（集成测试）各次尝试加入外层事务，需要重试时外层事务会被标记回滚。新建 API Key 先锁软件行（`lockApplication`），保证并发下最多 10 个有效密钥。新增写路径要遵守同一顺序。

### Redis 缓存协议（改动前必须理解）

MySQL 是唯一数据源，Redis 无持久化。缓存键带随机“代标记”：用户 `gen:u`、软件 `gen:a`、密钥 `gen:k`、域 `gen:d:{dept}:{app}`，外加全局 `epoch`。改变授权状态的写操作必须在事务内调用 `PermissionCache.touchUser/touchApplication/touchKeys/touchDomain`：提交前替换代标记并 INCR busy，提交后再替换，完成后 DECR。读取方 Lua 原子读代标记与 busy，busy 时不回写；结果键由读取时的代标记组成。结果 TTL 60 秒，且不超过结果中最早到期的角色分配（`ContextService.Payload.refreshAt`，命中时已过该时刻也重新查库）。

- 当前治理写路径的 touch：`RbacService.userRoles`（`ids` 为完整集合；`expiries` 逐角色到期、`expiresInDays` 作用于其余提交的角色，两者都未涉及的已有角色保留原期限；每次授予或改期限换新 `grant_id`）→ `touchUser`；`GovernanceService.approve` → `touchUser`；`closeReview`（只删除 `grant_id` 仍与快照相同的分配，其余条目记 `skipped`）→ 对每个被回收用户 `touchUser`；`importManifest` → 每个涉及部门 `touchDomain`。`importUsers` 只建/改用户资料并补充成员关系，不调用 touch；软件限额修改不涉及授权结果。分配到期在到期时刻没有写操作，靠 `refreshAt` 保证即时；`MaintenanceService.expireAssignments` 每 60 秒调用 `GovernanceService.expireAssignments`（每批 500）：按主键条件删除仍已到期的行（多实例只有一个删除成功），对受影响用户 `touchUser`，发 `user.roles.expired`（data 含 userId/externalId/departmentId/roleIds），审计 `USER_ROLES_EXPIRED`（actor `system`）。
- 新增任何影响授权结果的写路径时，必须调用对应 touch，并在 `PermissionCacheIntegrationTest` 补用例。touch 要求处于事务中，否则抛异常。
- 改变授权状态的写路径还应在同一事务中调用 `WebhookService.emit/emitAll`（事务外盒），事件名限 `WebhookService.EVENT_TYPES`；新增事件时同步更新该列表、前端 `labels.ts` 和 SDK `MelonHubAuthorizer.EVICT_EVENTS`（如需清缓存）。
- Redis 异常触发 5 秒熔断走 MySQL；无法标记的写记为遗漏，本实例在推进 epoch 前绕过 Redis，`recover()` 每 2 秒尝试推进。Lua 内拼接键名，只支持单节点 Redis。
- `GlobalRateLimiter` 使用 `rl:` 前缀键，有独立的 5 秒熔断（不共享 `PermissionCache` 的状态）：Redis 调用失败后 5 秒内直接用实例内令牌桶。
- `CACHE_ENABLED=false` 可完全关闭缓存（全局限流随之退回实例内）。

### 管理员与凭据

控制台权限按功能配置：16 项功能（`AdminFunction` 枚举，与 `admin_functions` 表一致），每个 `/api/admin` 处理函数用 `@RequiresFunction` 声明所属功能，由 `AdminFunctionGuard` 拦截校验，未声明的接口一律拒绝；管理员角色（`admin_roles`）是一组功能，内置 SUPER_ADMIN / ADMIN / APP_ADMIN / AUDITOR 不可修改且保持原有权限（`AdminFunctionTest` 按旧规则逐接口核对），可在控制台新建自定义角色（只能授予自己拥有的功能）。软件范围属于账号（`all_applications` + `admin_account_applications`），处理函数用 `AdminAccess.application` 校验；列表按可见软件过滤。角色的功能每次请求重新读取，修改即生效。管理员按部门分级：账号组成树（`AdminTree`：根管理全部账号，其余管理子树；下级的角色功能、部门与软件范围为上级子集；`DEPARTMENT` 部门管理员是叶子、必须限定部门、不能有账号/角色管理功能）。账号与角色的修改先锁全部账号行（`lockAccounts`），在内存中校验修改后的树，通过后才写入。部门范围经 `Visibility`（软件、部门，null 表示不限）传入列表查询，授权域接口用 `AdminAccess.domain` 同时校验部门与软件；平台级功能（`AdminFunction.platformWide`）与用户批量导入要求全部部门。`.env` 的 ADMIN_* 只在账号不存在时创建初始超级管理员；`ADMIN_PASSWORD_RESET=true` 重启一次可恢复，之后改回 false。5 次失败（密码或动态口令）锁 15 分钟，会话 8 小时、空闲 30 分钟；保留至少一个启用的超级管理员。

两步验证：`Totp` RFC 6238（SHA1、30 秒、6 位，容忍 ±1 步）；setup 生成待绑定密钥，confirm 后生效；登录记录 `mfa_last_step`，同一步不能复用（停用两步验证同样检查）；错误码 `MFA_REQUIRED`/`MFA_INVALID`；停用需密码 + 口令；超级管理员 `POST /api/admin/accounts/{username}/mfa/reset`。

上下文 1 小时；API Key、上下文 token、会话 token 只存 SHA-256；管理员密码 BCrypt cost 12，12–100 个字符且 UTF-8 不超过 72 字节（超出拒绝，不截断；`.env` 初始密码超出则拒绝启动）。会话绑定 `credential_version`：改密、重置、改角色、停用、两步验证变更都递增版本；登录用条件插入创建会话，要求账号仍启用且版本、密码哈希、TOTP 密钥与校验时一致，否则 401 `BAD_CREDENTIALS`，因此并发改动或删除后同名重建都不会留下可用会话。账号名可含 `@`、`.`，不能以点开头。每软件最多 10 个有效 API Key（`application_keys`），可设有效期、单独吊销；`rotate-key` 是紧急轮换，吊销全部旧密钥。API Key 不下发浏览器；业务后端先认证用户再提交可信 externalId；平台不撤回已执行的外部业务操作。

Webhook：头 `MelonHub-Event`、`MelonHub-Delivery`、`MelonHub-Signature: t=<秒>,v1=hex(HMAC-SHA256(secret,"{t}.{body}"))`；每 5 秒认领最多 20 条（不超过发送线程池空余队列），交给 `melonhub.webhooks.concurrency`（默认 4）个发送线程并行推送，调度线程不等 HTTP；认领与写回都以 `attempts` 为条件，租约过期后旧线程不能覆盖新结果；停用的 Webhook 不投递但保留队列；超时 5 秒，不跟随重定向，失败退避 min(60,2^(n-1)) 分钟，共 8 次后 FAILED；`SENDING` 租约 5 分钟。SDK 验签默认容差 300 秒。URL 只校验 http(s) 格式，未做目标网段限制。

审计哈希链：`AuditSealService` 每 10 秒锁 `audit_seal` 单行，按封存顺序分配 `seal_seq`（晚提交的小 ID 记录接在链尾），`chain_hash = SHA-256(prev_hash | 规范化记录)`；`seal_seq >= v2_from_seq` 的记录用 v2 形式（序号开头、每字段“长度:值”），更早的保持原形式。`audit_seal` 记链尾 `last_seq/last_hash` 与锚点 `anchor_seq/anchor_hash`；保留期清理按链前缀删除并更新锚点。`/api/admin/audit/verify?fromSeq=&limit=`（limit ≤ 50000）返回 `complete/nextSeq` 与第一处 `problem`：`LINK_BROKEN`、`MODIFIED`、`TAIL_MISSING`。链与数据同库，不是外部存证。

`ApiGuard` 默认拒绝，只有 `/api/health`、`/api/auth/login` 公开；路径必须匹配规范形式，否则 400 INVALID_PATH（防 `;`、`%` 编码绕过）。不要放宽该正则。登录限流每来源地址每分钟 10 次，业务 API 按软件每秒限额，均经 `GlobalRateLimiter`。

## 4. 配置与启停

`application.yml` 从根目录或 backend 上一级加载 `.env`，缺省 cloud；当前 `.env` 选择 local（`LOCAL_DB_*`、`LOCAL_DB_CONTAINER=xmudemo-mysql`、`TEST_DB_TARGET=local`）。Redis：`CACHE_ENABLED`、`REDIS_HOST/PORT/PASSWORD/DATABASE/CONTAINER`。可选调优：`APP_RATE_LIMIT`（默认 1000，0 不限）、`GLOBAL_RATE_LIMIT`（默认 true）、`WEBHOOK_DELIVERY_ENABLED`（默认 true）、`WEBHOOK_RETENTION_DAYS`（默认 30）、`DB_POOL_SIZE`（20）、`AUDIT_RETENTION_DAYS`（180）、`ADMIN_IDLE_MINUTES`（30）、`MANAGEMENT_PORT`（8081）、`HTTP_MAX_THREADS`（200）。仅在 `application.yml` 中固定、没有环境变量的项：`melonhub.webhooks.timeout-millis`（5000）、`melonhub.webhooks.max-attempts`（8）、`melonhub.webhooks.concurrency`（4）、`melonhub.rate-limit.login-per-minute`（10）、`melonhub.maintenance.enabled`（true，同时控制定时清理和审计封存）；TOTP issuer 固定为 `MelonHub`（代码中）。`.env` 中不要留 `KEY=` 空值，Spring 会当作空字符串。

`service-tools.ps1` 的 `Import-MelonRuntimeConfiguration` 对空值删除环境变量而不是设为空串（空 `SERVER_ADDRESS` 曾导致监听全部网卡）。新增配置项时同时加入该列表和 `.env.example`。

`CloudDatabaseConfiguration` 在 Flyway 前验证入口、端口、固定 schema、TLS 与 JDBC 参数，local 仅允许 loopback；`CloudTestDatabaseGuard` 保护测试库。JDBC 使用 `serverTimezone=SERVER`；JSON 时区 Asia/Shanghai。

```powershell
./scripts/start-all.ps1            # 拉起 MySQL 容器、创建/拉起 Redis 容器、按需构建、启动前后端
./scripts/start-all.ps1 -NoBrowser
./scripts/start-all.ps1 -Rebuild
./scripts/stop-all.ps1             # 停止本项目 Java/Vite 与带标签的 Redis，保留共享 MySQL
```

前端固定 5174（5173 留给其他项目），后端 8080，监控 8081，均只绑定 127.0.0.1；端口被占用时拒绝启动。启停持有 `.local/services.lock`，地址写 `.local/services.json`，日志在 `.local/logs/`（脱敏）。修改配置/源码后先停再启。

前端依赖用 `npm ci --ignore-scripts`：本机 PATH 超过 8191 字符时 cmd.exe 运行的生命周期脚本找不到 node。手动 `npm run build` 同样受影响，需在当前终端缩短 PATH（见 `doc/本地Docker运行.md`）。

`.run/` 含 Backend、Frontend、Stack、Tests 配置。

## 5. 验证证据（2026-10-19 本机，复审修复后）

最新手机复验：实际 375×812 走通创建部门/软件/角色/权限/成员、两类复制、跨页跨软件追加、个别调整、部分失败后继续、审批与复核关闭、全局搜索、诊断、导出和审计链校验。四类管理员入口符合权限范围；接口回读确认分配、期限、回收与首次上下文语义。14 页手机明暗无横向溢出，320×640 日期/时间及范围选择可用，1280×720 日期布局保持原样。前端 1 项测试、最终类型检查与构建通过；仅 4 个前端文件及文档改动，未重跑后端/SDK。临时数据已清理，原有 2 部门、2 软件、1 用户、1 管理员保留，审计与治理历史保留。未做实体手机、触屏手势与软键盘验收。见 `doc/验证记录.md` 第 1.0.2 节。

此前对象内配置流程：前端 1 项会话竞态测试、类型检查、构建通过；浏览器走通新建部门/软件/角色/权限/成员、两类批量复制、跨页跨软件授权与个别调整、部分失败后继续、添加已有用户、全局搜索及四类管理员入口。接口回读验证期限及既有配置保留、权限实际允许/拒绝。桌面明暗正常；当时尺寸设置未生效，手机未计为复验通过（现已由上段补验）。临时验收数据已清理。仅前端改动，当时未重跑下述后端 121 项和 SDK 35 项，详情见 `doc/验证记录.md` 第 1.0.1 节。

最新全量审查后补修：后端 `mvn package` **121 项**（Rbac 29、Security 26、Governance 32、Concurrency 3、PermissionCache 5、PermissionCacheOutage 2、GlobalRateLimiter 1、DatabaseTargetGuard 11、CloudTestDatabaseGuard 9、ConsoleService 2、WebhookService 1），SDK **35 项**，均 0 失败/错误/跳过；前端会话竞态用例 1 项、类型检查与构建通过。浏览器实测跨页选人→多角色追加→个别调整、期限不变、过期表单拒绝保存、五种普通列表导出、软件状态筛选、具体事件 Webhook 编辑，以及批量弹窗明暗/手机布局；详情见验证记录第 1.0 节。

本轮环境故障：Docker Desktop 默认管理管道和 Windows 的 Redis 6380 转发不响应，容器内 Redis 正常。本机 Linux 引擎管道仍可用，检查/重启仅限带项目标签的 `melonhub-redis`。最终缓存测试通过 `.local/minfix-redis-bridge.py` 临时通道连接同一容器完成，测试使用 DB 1 和测试前缀，验证结束停止该通道。`.env`、产品 Redis 配置未改，正常入口仍可能 `cache=DOWN` 并回退 MySQL。不要把 121 项通过解释为 Docker 转发已恢复；不要为此擅自重启共享 Docker Desktop。临时 UI 验收数据已按 ID 清理，审计保留。

以下为本日较早三批复审的验证记录，数量不代表最新版本：

- `./scripts/test-backend.ps1`：**112 项**（RbacIntegration 29、SecurityIntegration 24、GovernanceIntegration 27、ConcurrencyIntegration 3、ConsoleServiceTest 2、PermissionCacheIntegration 4、PermissionCacheOutage 2、GlobalRateLimiter 1、DatabaseTargetGuard 11、CloudTestDatabaseGuard 9），0 失败/错误/跳过。`ConcurrencyIntegrationTest` 用真实并发线程验证动态口令不能被并发登录共用、角色成员替换不与用户侧修改死锁、慢 Webhook 接收方不占用调度线程。
- Java SDK `sdk/java`：`mvn test` 30 项通过。
- 前端 `vue-tsc -b` 与 `vite build` 通过（antd 独立 chunk 约 1.13MB）。
- 本机重启后 Docker Desktop 不会自动运行：先启动 Docker Desktop，再跑测试或 `start-all.ps1`（Redis 容器由脚本拉起）。
- 真实栈（`start-all.ps1 -NoBrowser -Rebuild`，V4/V5 自动应用）：端到端 38/38（工具 `.local/v3-tools/v3-e2e.mjs`）；补充 6/6（`p5-check.mjs`：系统状态固定字段、删除后同名重建账号的旧会话与旧密码失效、角色成员替换）。脚本都要登录，登录限流每分钟 10 次，连续运行之间等约 60 秒；结束后 `cleanup.mjs` 清理。
- 浏览器（`shots.mjs`，参数为页面列表与模式 `light,dark,mobile,w820`，输出目录取 `SHOTS_OUT`）：14 页 × 1440 浅色/深色/375 手机无横向溢出；修正系统状态页 Descriptions 列宽警告后复查无控制台错误。`drawer-check.mjs` 只读检查角色成员抽屉打开、加载、重新打开。截图 `output/ui/r1019/`、`r1019-fix/`。
- 清理后剩余用户、部门、软件各为用户自己原有的“1”，管理员账号只有 admin（MFA 停用）。
- 2026-10-18（V3）另验证过且本轮未重做：角色到期任务约 30 秒内删除分配并写审计与 `user.roles.expired`；双实例（8080 + 临时 18080/18081，关闭维护任务）经 Redis 共享登录限流，交替请求 10×401 后 2×429；删除冲突提示（409 + 具体剩余数量）；手机适配检查（375×812 触屏模拟、820/1024/1280 宽，工具 `mobile-audit.mjs`，截图 `output/ui/mobile-*`、`w820`）。
- 未验证：IDEA 图形运行按钮；云端 MySQL；生产容量；数据库不可用时的系统状态页只由单元测试覆盖（共享 MySQL 不能停）；未重新压测，也未重新做 Redis 宕机演练和双实例缓存一致性（企业化改造轮结果：限流关闭单实例约 3584 次/秒、Redis 停止时 64 并发约 661 次/秒全部成功、双实例 200 次校验 0 次旧结果、宕机演练 11/11，见 `doc/验证记录.md` 第 9 节）。
验证工具在忽略目录 `.local/`（`e2e.ps1`、`cross.ps1`、`drill.ps1`、`probe.ps1`、`load.mjs` 等），会读取 `.env` 但不打印凭据，不是产品模块。`cross.ps1`/`drill.ps1` 依赖 e2e 生成的临时状态文件，复用前先重新运行 e2e，结束后清理。另起实例的方式：`--server.port=18080 --management.server.port=18081 --melonhub.maintenance.enabled=false`，压测关闭限流再加 `--melonhub.rate-limit.application-per-second=0`；注意全局限流下两个实例共享 Redis 计数，用完停止。

浏览器检查不要用会同步 Windows 账号的默认 Edge 配置；使用 `--inprivate --disable-extensions --disable-sync --user-data-dir=<.local 下临时目录>`，结束后删除该目录。

云端历史：两台 ECS 上 Swarm MySQL `lab_mysql` 9.2.0，曾通过 25 项测试与完整浏览器数据链验证；root 密码统一改密已完成，不要重复改密或重试历史密码。最近两轮云端 MySQL/SSH 未握手或未连接，未重新验证。工具环境 `.local/cloud-tools/`，`scripts/cloud-database.py verify` 只读。参考 PDF 不是执行授权，不得再次 swarm init、stack deploy、初始化 SQL 或重置账号。

## 6. 差距分析与后续路线

已补齐：分级管理员与登录防护、两步验证、API Key 生命周期、审计详情与导出、审计哈希链、授权诊断、角色成员视图、配置复制、权限缓存与高峰保护、跨实例全局限流与按软件限额、健康与指标、Java SDK、Webhook 变更通知、用户批量导入与权限清单、授权治理（分配有效期、申请审批、定期复核）。

仍未做（按建议优先级）：

1. 高可用部署：Redis 主从 + 哨兵、HTTPS 反向代理与容器化部署清单、CI 流水线（含 Playwright 端到端用例）。
2. 业务侧 SDK：Node、Go 版本（Java 已完成）。
3. 审计外部存证：导出到 SIEM/WORM 存储（当前哈希链与数据同库）。
4. 用户同步：HR/SCIM 自动同步（当前为手工批量导入）。
5. 授权治理深化：审批人路由（角色负责人/多级审批，当前任何有该软件写权限的管理员都可审批）、按周期自动发起复核、审批/复核待办的邮件或 IM 提醒、清单导入前差异预览（dry-run）、授权配置版本与回滚。
6. 管理面：按部门委派的管理员范围（当前软件管理员只按软件划分）、可脚本化的管理 API 令牌、管理员会话列表与下线、密码有效期/历史策略、控制台来源 IP 白名单。
7. 小改进：Webhook 目标地址白名单、Webhook/TOTP 密钥加密存储、TOTP issuer 可配置；Grafana 面板与告警规则样例。
8. 控制台：国际化、按需拆分 antd 体积。企业 SSO 明确不在范围内；职责分离不在 RBAC0 范围内。

## 7. 已知限制

- Flyway 对 MySQL 8.3 与 9.2 均提示超出已测试版本范围；本项目迁移和用例实际通过，升级依赖时需再验证。
- 缓存要求单节点 Redis。限流默认经 Redis 跨实例共享（1 秒固定窗口，窗口边界可能出现短时双倍）；Redis 不可用、`CACHE_ENABLED=false` 或 `GLOBAL_RATE_LIMIT=false` 时退回按实例令牌桶。多实例时定时任务在每个实例运行（删除幂等，审计封存靠行锁，Webhook 投递靠条件认领）。
- `user.roles.expired` 由每分钟一次的任务发出，最多晚约 1 分钟；校验结果在到期时刻即生效。
- 压测数字来自上一轮 Docker Desktop 单机，不是生产容量承诺；本轮未重测。
- 未验证 IDEA 图形运行按钮、productdemojpa 业务 HTTP。当前是本机开发入口，不是公开部署的生产站点。
- `.env` 按 Java properties 与 PowerShell KEY=value 读取，特殊字符和引号需验证双方一致，不得盲目给密码加引号。
- 默认忽略 .env/.env.*（仅放行 .env.example）、.local、output、target、dist、node_modules、日志。不得把真实配置复制到其他共享文件。
