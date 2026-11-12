# 接口说明

本文面向两类读者：接入 MelonHub 的业务软件后端开发人员（第 1–4 节），以及需要通过接口自动化管理平台的运维人员（第 5–7 节）。控制台操作见 [项目使用说明书](使用说明书.md)，模型、缓存与信任边界见 [架构说明](ARCHITECTURE.md)。Java 业务后端可直接使用 [Java SDK](../sdk/java/README.md)。

## 1. 地址与认证

| 入口 | 前缀 | 认证方式 | 调用方 |
| --- | --- | --- | --- |
| 业务 API | `http://127.0.0.1:8080/api/v1` | 请求头 `X-API-Key`，上下文接口另需 `X-Context-Token` | 业务软件的可信后端 |
| 管理 API | `http://127.0.0.1:8080/api/admin` | `Authorization: Bearer <管理员会话 token>` | 管理控制台、运维脚本 |
| 公共接口 | `/api/health`、`/api/auth/login` | 无 | 健康探测、管理员登录 |
| 监控端点 | `http://127.0.0.1:8081/actuator/*` | 仅本机回环 | 监控系统 |
| Webhook 推送 | 业务软件登记的地址 | `MelonHub-Signature` 签名 | 平台 → 业务软件 |

浏览器控制台通过 Vite 的 `/api` 代理访问后端。服务默认只监听 `127.0.0.1`，公开部署需另行规划 HTTPS 反向代理和可信入口。

除上表列出的公共接口外，`/api` 下的所有请求默认拒绝：`/api/v1/**` 必须带有效 API Key，其余路径（包括 `/api/auth/me`、`/api/auth/mfa/*`）必须带有效管理员会话。

API Key 对应的软件由服务端决定，调用方无法通过参数切换到其他软件。密钥只交给业务后端保存，不能下发到普通用户的浏览器或客户端。

业务后端必须先认证真实用户，再提交可信的 `externalId`。平台只做授权判断，不代替业务系统登录，也不执行业务操作。

## 2. 通用约定

- 请求体和响应体均为 JSON，请求须带 `Content-Type: application/json`，否则返回 415。
- 时间字段为带时区信息的 ISO-8601 字符串（`Z` 或 `+08:00` 结尾），请按带时区的时间解析。
- 路径必须是规范形式：每段只能由字母、数字和 `_ . @ -` 组成，不能以点开头，不能包含 `;`、`%` 转义、`..`、连续斜杠或结尾斜杠。不规范的路径一律返回 `400 INVALID_PATH`，不会进入任何业务处理。
- 每个响应都带 `X-Request-Id`。调用方可自带 8–64 位 `[A-Za-z0-9._-]` 的请求编号，服务会原样回传并写入日志；否则由服务生成。排查问题时请提供该编号。
- 响应统一带 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer` 和严格的 `Content-Security-Policy`。
- 分页接口的参数为 `page`（从 1 开始，默认 1）和 `size`（默认 20，最大 200），响应形如 `{"items":[...],"total":123,"page":1,"size":20}`。
- 关联接口中的 `ids` 是整体替换：传入列表即最终结果，空数组表示清空，并非追加。

## 3. 业务 API

### 3.1 接口清单

| 方法 | 路径 | 请求与目的 |
| --- | --- | --- |
| GET | `/departments` | 查询全部部门 |
| GET | `/departments/{departmentId}/users` | 查询部门成员 |
| GET、POST | `/departments/{departmentId}/roles` | 查询或创建本软件在该部门的角色；创建体为 `{"name":"只读角色","code":"reader","description":""}`，返回 `{"id":...}` |
| PUT、DELETE | `/departments/{departmentId}/roles/{roleId}` | 更新或删除角色；更新字段与创建一致 |
| PATCH | `/departments/{departmentId}/roles/{roleId}/status` | 启停角色；`{"enabled":false}` |
| GET、POST | `/departments/{departmentId}/permissions` | 查询或创建权限；创建体为 `{"name":"查看项目","code":"project:read","resource":"project","action":"read"}` |
| PUT、DELETE | `/departments/{departmentId}/permissions/{permissionId}` | 更新或删除权限；更新字段与创建一致 |
| PUT | `/departments/{departmentId}/roles/{roleId}/permissions` | `{"ids":[1,2]}`，整体替换该角色的权限集合 |
| GET、PUT | `/departments/{departmentId}/users/{userId}/roles` | 查询（返回 `{"roleIds":[...]}`）或整体替换该用户在本域的角色；更新体与控制台相同（第 5.5 节）：`{"ids":[1,2],"expiresInDays":30,"expiries":[{"roleId":2,"expiresAt":"2026-12-31T18:00:00+08:00"}]}`，`expiresInDays`、`expiries` 均可省略 |
| POST | `/contexts` | `{"externalId":"sample-user","departmentId":1,"activeRoleIds":[1],"switchDepartment":false}`，创建上下文；`switchDepartment` 省略或为 true 时，目标部门与用户当前部门不同会切换部门，为 false 时拒绝切换（见第 3.3 节） |
| GET | `/contexts/effective` | 查询当前有效角色和权限，需要上下文头 |
| POST | `/contexts/check` | `{"permissions":["project:read"]}`，逐项校验，需要上下文头 |
| DELETE | `/contexts` | 注销该上下文，需要上下文头 |
| GET | `/users/access?externalId=&departmentId=` | 只读查询某用户在本软件该部门已分配的角色和有效权限，不创建上下文、不切换部门 |
| POST | `/access-requests` | 代员工提交角色申请，见第 3.5 节 |
| GET | `/access-requests?externalId=` | 查询该员工在本软件提交过的申请，最新在前 |
| POST | `/access-requests/{id}/cancel` | `{"externalId":"sample-user"}`，撤回本人仍待审批的申请 |

路径中的 ID 仅为示例，应先查询真实 ID。角色和权限编码允许 `[a-zA-Z0-9_.:-]`，最长 100 位；权限编码区分大小写，服务不会解析其中的冒号或业务含义。`resource` 与 `action` 只是描述信息，不构成额外授权规则。

`activeRoleIds` 省略时激活全部已分配角色，传空数组表示不激活任何角色。传入的角色必须是该用户在当前域已分配且启用的角色，否则返回 403。

用户身份和部门成员关系只能由平台管理员维护，业务 API 只能管理本软件内的角色、权限及关联。

`GET /users/access` 的响应为 `{"userId":7,"departmentId":1,"applicationId":3,"roles":[{"id":11,"name":"阅读者","code":"reader","enabled":true,"expiresAt":null}],"permissions":["project:read"]}`，`permissions` 只计入启用且未到期的角色。它适合在业务界面上展示“我有哪些权限”，不能代替每次操作前的 `/contexts/check`。

### 3.2 创建与校验示例

以下为 Bash/curl 示例，所有凭据都是占位符：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/contexts \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: YOUR_SOFTWARE_API_KEY' \
  -d '{"externalId":"sample-user","departmentId":1}'

curl -X POST http://127.0.0.1:8080/api/v1/contexts/check \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: YOUR_SOFTWARE_API_KEY' \
  -H 'X-Context-Token: RETURNED_CONTEXT_TOKEN' \
  -d '{"permissions":["project:read","project:create"]}'
```

创建上下文的响应：

```json
{
  "contextToken": "只在创建时返回一次",
  "userId": 7, "departmentId": 1, "applicationId": 3,
  "contextVersion": 4,
  "expiresAt": "2026-10-18T07:30:00Z",
  "roles": [{"id": 11, "name": "阅读者", "code": "reader"}],
  "permissions": ["project:read"]
}
```

`GET /contexts/effective` 返回同样的字段，但不含 `contextToken`。上下文有效期为 1 小时，原始 token 由调用方妥善保管，平台只保存其哈希。

校验响应：

```json
{"allowed":false,"decisions":{"project:read":true,"project:create":false},"contextVersion":4}
```

一次最多校验 100 个权限编码。`decisions` 逐项给出结果，只有全部通过时 `allowed` 才为 true；不存在或未授权的权限一律为 false。

### 3.3 部门切换与失效

同一用户的当前部门跨软件、跨设备共享。在另一部门创建上下文，会使该用户旧部门的全部软件上下文失效；切回原部门也不会恢复旧 token。同一部门为不同软件创建的上下文可以同时有效。

只有用户在业务界面上主动切换部门时才应切换。普通的权限校验应传 `"switchDepartment":false`：用户尚无当前部门或目标部门就是当前部门时正常创建；否则返回 `409 DEPARTMENT_MISMATCH`，不改变当前部门，也不影响其他上下文，业务系统据此提示用户先切换部门。Java SDK 的 `MelonHubAuthorizer.hasPermission` 即按此方式创建上下文，只有 `switchDepartment` 方法会切换部门。

以下变更会让相关上下文立即失效（返回 `CONTEXT_INVALID`）：切换部门、修改用户的外部标识、把用户移出其当前部门、重新分配该用户在该域的角色、通过角色成员调整移入或移出、批准该用户在该域的角色申请、权限复核关闭时回收其在该域的角色、停用或删除用户、停用或删除软件、管理员强制下线、上下文到期或被注销。

角色停用、权限移除、角色权限调整、导入权限清单在下一次校验时生效，旧 token 仍然有效但不再包含被移除的权限。平台内部用 Redis 缓存加速校验，但每次授权变更都会主动使缓存失效，正常情况下变更提交后的下一次校验就能看到新结果。

带有效期的角色分配到期后立即不再计入有效权限，也不需要重新创建上下文。缓存有效期不超过结果中最早到期的角色分配，命中时也会检查该到期时刻，因此不会继续使用包含已到期角色的校验结果。

### 3.4 接入建议

- 每次受保护的业务操作之前都调用 `/contexts/check`，不要在业务侧缓存“允许”的结果反复使用。平台无法撤回已经在业务系统中执行的操作。
- 收到 `CONTEXT_INVALID` 时丢弃旧 token，根据已认证用户和其当前部门重新创建上下文。
- 收到 `429 RATE_LIMITED` 或 `503 SERVICE_BUSY` 时，按 `Retry-After` 秒数退避后重试，不要立即密集重试。
- 网络错误、超时、5xx 和未知响应都按“拒绝”处理，不能默认为有权限。
- 计划内更换 API Key 时，先在控制台新建密钥并替换业务配置，确认旧密钥的“最近使用”时间不再更新后再吊销旧密钥，可做到不停机。
- 需要及时清理本地会话或上下文缓存时，登记 Webhook（第 3.6 节），收到授权变更事件后丢弃相关缓存。

Java 业务后端可以使用 `sdk/java` 中的 `melonhub-client`：它封装了上下文创建与缓存、批量校验、`CONTEXT_INVALID` 自动重建一次、429/503 按 `Retry-After` 退避（默认最多 2 次）、5xx 与网络故障 fail-closed、Spring Boot 自动装配与 `@RequirePermission` 注解，以及 Webhook 验签。用法见 [SDK 说明](../sdk/java/README.md)。

### 3.5 员工自助申请角色

业务系统在员工认证后，可以代其向平台提交角色申请，由管理员在控制台“审批中心”批准或驳回：

```json
POST /api/v1/access-requests
{"externalId":"sample-user","departmentId":1,"roleId":11,"reason":"负责新项目，需要审批权限","durationDays":30}
```

- `reason` 必填，最长 500 字；`durationDays` 可选（1–3650），表示希望的授权天数，省略为永久。
- 用户必须是该部门成员（否则 403），角色必须属于本软件在该部门的授权域（否则 404）；同一用户对同一角色已有待审批申请时返回 409。该限制由数据库唯一约束保证，并发重复提交也只有一条成功。
- 响应为申请记录，`status` 依次可能为 `PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`。批准后返回的 `grantExpiresAt` 为授权到期时间（永久授权为 null）。
- 申请记录保存提交时的用户姓名、外部标识、角色名称与编码、部门名称。之后删除用户、角色或部门，记录仍保留，对应的 `userId`、`roleId`、`departmentId` 变为 null。
- 批准即写入角色分配，并使该用户在该域的已有上下文失效；业务系统需重新创建上下文才能拿到新权限。
- 撤回只对本人、本软件、仍为 `PENDING` 的申请有效，其他情况返回 404 或 409。

### 3.6 接收 Webhook

管理员可以为每个软件登记 Webhook（第 5.11 节）。授权变更时，平台在同一数据库事务中写入待投递记录（事务外盒），再由后台任务异步推送，因此变更提交即保证事件会被投递，变更回滚则不会产生事件。

请求为 `POST`，`Content-Type: application/json`，请求头：

| 请求头 | 内容 |
| --- | --- |
| `MelonHub-Event` | 事件类型，例如 `user.roles.changed` |
| `MelonHub-Delivery` | 本次投递的唯一编号（UUID），重试时保持不变，可用于去重 |
| `MelonHub-Signature` | `t=<Unix 秒>,v1=<十六进制 HMAC-SHA256>` |

请求体：

```json
{"type":"user.roles.changed","applicationId":3,"occurredAt":"2026-10-18T07:30:00Z","data":{"userId":7,"externalId":"sample-user","departmentId":1}}
```

| 事件 | 触发 |
| --- | --- |
| `user.updated`、`user.enabled`、`user.disabled`、`user.deleted` | 用户编辑、启停、删除；平台级事件，推送到所有订阅它的软件，`applicationId` 为 0 |
| `user.roles.changed` | 用户在本软件某域的角色被重新分配，或角色成员被调整（后者 `data` 含 `roleId`、`added`、`removed`） |
| `user.roles.expired` | 用户在本软件某域的角色分配到期，`data` 含 `userId`、`externalId`、`departmentId`、`roleIds`；后台每分钟检查一次，同一批到期的角色合并为一个事件 |
| `role.enabled`、`role.disabled`、`role.deleted`、`role.permissions.changed` | 本软件的角色启停、删除、角色权限调整（含清单导入产生的启停及权限变化） |
| `permission.updated`、`permission.deleted` | 本软件的权限修改、删除，`data` 含 `permissionId`、`departmentId` |
| `user.contexts.revoked` | 用户强制下线，`data` 含 `userId`、`externalId` |
| `application.enabled`、`application.disabled` | 本软件启停 |
| `apikey.revoked` | 吊销密钥或紧急轮换 |
| `access.approved`、`access.rejected` | 角色申请被批准或驳回；驳回事件的 `data.comment` 为驳回理由（未填写时为空字符串） |
| `review.closed` | 权限复核关闭且实际回收了授权 |
| `webhook.test` | 控制台“发送测试事件” |

验签方法：取原始请求体字符串（不要先反序列化再拼接），计算 `HMAC-SHA256(secret, t + "." + body)` 的十六进制小写结果，与 `v1` 做常量时间比较；同时检查 `t` 与当前时间之差，Java SDK 默认容差 300 秒，超出视为重放。平台本身不规定容差，接收方可按需收紧。

投递规则：返回 2xx 视为成功；其他状态码、超时（5 秒）或连接失败都会重试，不跟随重定向。第 n 次失败后等待 min(60, 2^(n−1)) 分钟再试（1、2、4、8、16、32、60 分钟），共 8 次仍失败则标记为 `FAILED`。投递语义为“至少一次”，接收方应按 `MelonHub-Delivery` 幂等处理。事件只用于提醒业务侧清理缓存或会话，授权结论仍以 `/contexts/check` 为准。

## 4. 限流与健康检查

每个软件的业务 API 调用按软件计数，默认每秒 1000 次（`.env` 的 `APP_RATE_LIMIT`，0 表示不限制）；管理员可以为单个软件设置独立上限（第 5.3 节），修改后各实例最多约 30 秒内生效。超过后返回 `429 RATE_LIMITED` 并带 `Retry-After: 1`。

默认启用跨实例全局限流（`GLOBAL_RATE_LIMIT=true`）：计数保存在 Redis 中，按 1 秒固定窗口统计，多个后端实例共享同一个额度。Redis 不可用、关闭缓存（`CACHE_ENABLED=false`）或设置 `GLOBAL_RATE_LIMIT=false` 时，自动退回各实例内存中的令牌桶，此时额度按实例分别计算。Redis 调用失败后，该实例在 5 秒内不再尝试 Redis，直接使用本地令牌桶。

管理员登录按来源地址限制为每分钟 10 次，同样经 Redis 在实例间共享。

`GET /api/health` 无需认证：

```json
{"status":"UP","database":"UP","cache":"UP","model":"RBAC0"}
```

数据库可用时返回 200，数据库不可用时返回 503 且 `status` 为 `DOWN`。`cache` 为 `UP`、`RECOVERING`（Redis 刚恢复、正在完成缓存纪元推进）、`DOWN`（已回退到数据库实时校验）或 `DISABLED`；缓存降级不影响授权正确性，也不会让健康检查失败。

## 5. 管理 API

### 5.1 登录、会话与两步验证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | `{"username":"admin","password":"占位符","mfaCode":"123456"}`，未启用两步验证时 `mfaCode` 可省略；返回 `token`、`expiresIn`（秒）、`idleTimeout`（秒）、`username`、`displayName`、`role`、`applicationIds`、`mfaEnabled` |
| GET | `/api/auth/me` | 查看当前会话身份，含 `mfaEnabled` |
| POST | `/api/auth/logout` | 注销当前会话 |
| PUT | `/api/auth/password` | `{"currentPassword":"...","newPassword":"12–100 个字符"}`；成功后该账号的其他会话全部下线 |
| POST | `/api/auth/mfa/setup` | 生成待绑定的 TOTP 密钥，返回 `secret` 和 `otpauth://` 形式的 `uri`；此时尚未启用 |
| POST | `/api/auth/mfa/confirm` | `{"code":"123456"}`，用认证器上的第一个有效口令确认绑定，成功后启用 |
| POST | `/api/auth/mfa/disable` | `{"password":"当前密码","code":"123456"}`，停用两步验证 |

管理员会话最长 8 小时，空闲 30 分钟（`ADMIN_IDLE_MINUTES`）自动失效。连续 5 次密码或动态口令错误会锁定账号 15 分钟（返回 `ACCOUNT_LOCKED`），超级管理员可以提前解锁。

密码为 12–100 个字符，且按 UTF-8 编码不超过 72 字节（BCrypt 只使用前 72 字节，超出的部分会被拒绝而不是截断；约等于 24 个汉字）。

会话绑定创建时的账号凭据。修改或重置密码、修改角色、停用账号、两步验证变更都会让旧会话失效；与这些操作同时进行的登录不会留下可用会话，而是返回 `401 BAD_CREDENTIALS`（“账号凭据已变更，请重新登录”）。删除账号会同时删除其会话；之后用同一账号名重新创建的账号与旧账号没有任何关联，旧会话和登录中途的请求都不能用于新账号。

两步验证采用 RFC 6238 TOTP（HMAC-SHA1、30 秒步长、6 位数字），兼容常见认证器应用，允许前后各一个步长的时钟偏差。启用后登录未带口令返回 `401 MFA_REQUIRED`，口令错误或已被使用返回 `401 MFA_INVALID`：同一时间步的口令登录成功后不能再次使用。已启用时再次调用 `setup` 返回 409，需先停用。丢失设备时由超级管理员重置（第 5.8 节）。

### 5.2 管理员角色与功能

控制台权限**按功能配置**：每个管理接口属于一项功能，管理员角色是一组功能。管理员账号选择一个角色，并单独设定可管理的软件（全部软件，或指定的软件）。请求时先检查角色是否有该接口的功能，再检查涉及的软件是否在账号的可管理范围内；任一不满足返回 `403 FORBIDDEN`。功能变化在已登录管理员的下一次请求即生效，无需重新登录。没有声明功能的管理接口一律拒绝。

| 功能编码 | 名称 | 包含的接口 |
| --- | --- | --- |
| `org.view` | 查看部门、用户与软件 | 部门、用户、软件列表，用户导出与授权详情，全局搜索 |
| `department.manage` | 维护部门 | 新建、修改、删除部门 |
| `user.manage` | 维护用户 | 新建、修改、停用、删除用户，批量导入，强制下线 |
| `application.manage` | 维护软件 | 接入、修改、停用、删除软件，软件限流 |
| `application.credentials` | 管理 API Key 与 Webhook | 新建、吊销、轮换密钥；登记、修改、删除、测试 Webhook |
| `domain.view` | 查看授权配置 | 角色、权限、成员、分配、概览、诊断、清单导出、权限反查、即将到期、密钥与 Webhook 列表 |
| `domain.configure` | 配置角色与权限 | 角色与权限的增删改、启停，角色的权限勾选 |
| `domain.assign` | 分配用户角色 | 用户角色、角色成员、批量追加 |
| `domain.transfer` | 复制配置与导入清单 | 跨部门复制、权限清单导入 |
| `governance.view` | 查看审批与复核 | 申请列表、复核列表与明细 |
| `request.approve` | 审批角色申请 | 批准、驳回 |
| `review.manage` | 发起与处理权限复核 | 发起复核、逐条决定、关闭复核 |
| `audit.view` | 查看与校验审计日志 | 审计查询、导出、哈希链校验 |
| `system.view` | 查看系统状态 | 系统状态看板 |
| `account.manage` | 管理管理员账号 | 第 5.8 节全部接口，查看角色列表 |
| `role.manage` | 管理管理员角色 | 第 5.13 节全部接口 |

四个内置角色保持原有权限，不能修改或删除：

| 角色 | 功能 |
| --- | --- |
| `SUPER_ADMIN` 超级管理员 | 全部 16 项 |
| `ADMIN` 平台管理员 | 除 `account.manage`、`role.manage` 外的 14 项 |
| `APP_ADMIN` 软件管理员 | 5 项查看功能与 `application.credentials`、`domain.configure`、`domain.assign`、`domain.transfer`、`request.approve`、`review.manage` |
| `AUDITOR` 审计员 | 5 项查看功能：`org.view`、`domain.view`、`governance.view`、`audit.view`、`system.view` |

升级时原软件管理员账号被设为“指定软件”，其余账号为“全部软件”。软件范围只限制按软件进行的操作（授权域、密钥、Webhook、审批、复核、清单）以及列表中显示的软件和审计记录；部门、用户等平台级功能不受软件范围限制。管理员角色是平台自身的操作权限，与平台中管理的 RBAC0 业务数据相互独立。

### 5.3 部门、软件与 API Key

| 方法 | 路径 | 说明与所需角色 |
| --- | --- | --- |
| GET | `/api/admin/departments` | 部门列表，含成员数和角色数；任意管理员 |
| POST、PUT、DELETE | `/api/admin/departments[/{id}]` | `{"name":"研发中心","code":"rd-center"}`；超级/平台管理员 |
| GET | `/api/admin/applications` | 软件列表（含 `rateLimit`）；软件管理员只看到被授权的软件 |
| POST | `/api/admin/applications` | `{"name":"OA","code":"oa","description":"","owner":"行政组"}`；返回 `id`、`keyId` 和一次性 `apiKey` |
| PUT、DELETE | `/api/admin/applications/{id}` | 更新或删除；删除会一并删除其 API Key、管理员授权、Webhook 和上下文 |
| PATCH | `/api/admin/applications/{id}/status` | `{"enabled":false}`；停用会清除该软件全部上下文，并使其 API Key 暂时不可用 |
| PUT | `/api/admin/applications/{id}/rate-limit` | `{"perSecond":500}`（0–100000）；`0` 表示不限制，`null` 恢复平台默认；超级/平台管理员 |
| GET | `/api/admin/applications/{id}/keys` | 密钥列表：名称、前缀、创建人、创建/过期/最近使用/吊销时间，不含原文 |
| POST | `/api/admin/applications/{id}/keys` | `{"name":"生产环境","expiresInDays":365}`（有效期 1–3650 天，省略表示永不过期）；返回一次性 `apiKey`；每个软件最多 10 个有效密钥 |
| DELETE | `/api/admin/applications/{id}/keys/{keyId}` | 吊销单个密钥，立即生效 |
| POST | `/api/admin/applications/{id}/rotate-key` | 紧急轮换：吊销该软件全部现有密钥并生成一个新密钥，返回 `{"apiKey":"..."}` |

删除部门或软件前会检查剩余依赖，有依赖时返回 `409 CONFLICT`，并在 `message` 中说明还剩什么，例如“部门下还有 1 名成员、1 个角色、1 个权限，请先移除后再删除”“软件下还有 2 个角色、2 个权限，请先删除，或改为停用”。删除部门时会一并清除前成员遗留的权限上下文（它们已无法通过校验）。删除记录的审计对象为部门或软件名称，详情中注明“编码 xxx”。

密钥写操作要求对该软件有写权限，即超级/平台管理员，或被授权该软件的软件管理员。“最近使用”时间约每分钟写入一次。

### 5.4 用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/users` | 分页查询；参数 `keyword`（姓名、外部标识或邮箱）、`departmentId`、`enabled`、`page`、`size`；每项含 `departmentIds` |
| POST、PUT | `/api/admin/users[/{id}]` | `{"name":"张三","externalId":"zhangsan","email":"","departmentIds":[1,2]}`；超级/平台管理员 |
| POST | `/api/admin/users/import` | 批量导入，见下文；超级/平台管理员 |
| GET | `/api/admin/users/export` | 按 `keyword`、`departmentId`、`enabled` 导出全部匹配用户，返回与列表相同的 `items/total/page/size` 结构，不限于当前页；任意管理员可读 |
| PATCH | `/api/admin/users/{id}/status` | `{"enabled":false}`；停用后其上下文立即失效 |
| DELETE | `/api/admin/users/{id}` | 删除用户及其成员关系、角色分配和上下文 |
| GET | `/api/admin/users/{id}/access` | 授权详情：用户信息、各授权域的角色与权限、当前有效上下文；按调用者可见的软件过滤 |
| DELETE | `/api/admin/users/{id}/contexts` | 强制下线该用户的全部上下文，返回 `{"revoked":n}` |

修改外部标识会使该用户现有的全部上下文失效；移出某个部门会同时删除该用户在该部门的角色分配。

批量导入一次最多 500 行：

```json
{"users":[{"name":"张三","externalId":"zhangsan","email":"","departmentCodes":["rd-center"]}]}
```

按 `externalId` 新建或更新用户，按部门编码补齐成员关系，**不会移除**已有成员关系，也不分配角色；已存在用户的外部标识不会被改写。每行单独校验（字段缺失、超长或格式错误只让该行失败，不会拒绝整个请求），逐行处理、部分成功：响应为 `{"created":3,"updated":1,"failed":[{"line":5,"externalId":"...","error":"部门编码不存在: xxx"}]}`，`line` 从 1 开始。

### 5.5 授权域：角色、权限与分配

以下接口（除注明外）都必须带查询参数 `departmentId` 与 `applicationId`。读接口要求对该软件有读权限，写接口要求有写权限。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET、POST | `/api/admin/roles` | 角色列表（每项含 `permissionIds` 和 `memberCount`）或创建角色 |
| PUT、DELETE | `/api/admin/roles/{id}` | 更新或删除角色 |
| PATCH | `/api/admin/roles/{id}/status` | 启停角色 |
| PUT | `/api/admin/roles/{id}/permissions` | `{"ids":[...]}` 整体替换角色权限 |
| GET、PUT | `/api/admin/roles/{id}/members` | 查询或整体替换角色成员 `{"ids":[用户 ID...]}`；成员必须属于该部门，被移入或移出的用户在本域的上下文失效。保存期间如有用户被同时加入该角色，服务会自动重试；连续 3 次仍冲突时返回 `503 SERVICE_BUSY`，稍后重试即可 |
| GET、POST | `/api/admin/permissions` | 权限列表（含 `roleCount`）或创建权限 |
| PUT、DELETE | `/api/admin/permissions/{id}` | 更新或删除权限 |
| GET、PUT | `/api/admin/users/{id}/roles` | 查询或整体替换某用户在本域的角色；更新体见下文 |
| GET | `/api/admin/assignments` | 分页列出该部门成员及其在本域的 `roleIds`，以及 `roleExpiries`（`{角色 ID: 到期时间}`，只含有到期时间的角色）；参数 `keyword`、`page`、`size` |
| POST | `/api/admin/assignments/batch` | 为一批部门成员追加多个角色；参数仍需 `departmentId`、`applicationId`，需要该软件写权限；请求体见下文 |
| GET | `/api/admin/assignments/expiring` | 参数 `days`（1–365，默认 7）、可选 `applicationId`，不需要 `departmentId`；列出到期时间在该范围内的角色分配（最多 500 条），含用户、角色、部门、软件、到期时间和授予人 |
| GET | `/api/admin/permissions/who` | 参数 `applicationId`、`code`（权限编码）、可选 `departmentId`；反查哪些用户通过哪些启用且未到期的角色持有该权限。`holders` 每项为一个“用户 × 部门”，含授予该权限的全部角色及到期时间，最多返回 500 项；`total` 为实际的“用户 × 部门”数，`truncated` 为 true 表示未全部返回 |
| GET | `/api/admin/overview` | 当前授权域与平台的统计概览 |

批量追加角色：

```json
{"userIds":[7,8],"roleIds":[11,12],"expiresInDays":30}
```

`userIds` 与 `roleIds` 各为 1–1000 项，所有用户必须属于指定部门，所有角色必须属于指定授权域。已有有效分配保持原期限和授予记录，未持有或已到期的所选角色按本次设置授予；`expiresInDays` 可省略（永久），或为 1–3650 天。整批在一个事务中完成，返回 `users`、`changedUsers`、`grantsAdded`。仅实际发生变化的用户在该域的上下文失效。

替换某用户在本域的角色：

```json
{"ids":[11,12,13],"expiresInDays":30,"expiries":[{"roleId":12,"expiresAt":"2026-12-31T18:00:00+08:00"},{"roleId":13,"expiresAt":null}]}
```

- `ids` 是该用户在本域的完整角色集合，不在其中的已有角色被移除。
- `expiries` 逐个设置角色的到期时间，`expiresAt` 为 null 表示永久；到期时间必须晚于当前时间、不晚于 3650 天后，且只能为 `ids` 中的角色设置。
- `expiresInDays`（1–3650）作用于 `ids` 中没有出现在 `expiries` 里的全部角色。
- 两者都没有涉及的角色：已有的保持原到期时间和授予记录，新增的为永久。因此只改一个角色的期限时，其他角色的期限不会被改动。

从已加载的表单保存时，应在 `expiries` 中携带全部选中且有期限的角色，即使期限未修改；若后台已清理到期分配，原期限会因已过期被拒绝，避免误建永久分配。

到期的分配立即不再计入有效权限：缓存的校验结果不会保留到其中最早到期角色的到期时刻之后。后台任务每分钟删除已到期的分配行，写入审计 `USER_ROLES_EXPIRED`，并发出 `user.roles.expired` 事件；上下文本身不失效。

### 5.6 权限诊断、配置复制与权限清单

`POST /api/admin/simulate` 只做计算，不创建上下文，也不切换用户的当前部门：

```json
{"userId":7,"departmentId":1,"applicationId":3,"activeRoleIds":null,"permissions":["order:approve"]}
```

响应包含 `eligible`（业务系统能否为该用户创建上下文）、`issues`（不能创建的原因，如用户停用、不是部门成员、激活了未分配角色）、`switchesDepartment`（创建上下文是否会切换当前部门）、`roles`（已分配角色及是否激活）、`permissions`（有效权限及授予它的角色 `grantedBy`），以及传入 `permissions` 时的 `decisions` 与 `allowed`。

`POST /api/admin/domains/copy` 把同一软件下源部门的权限和角色（含角色权限关系）复制到目标部门：

```json
{"applicationId":3,"sourceDepartmentId":1,"targetDepartmentId":2}
```

目标部门已存在的同编码权限会被复用，同编码角色会被跳过，不复制用户角色分配。响应为 `{"permissionsCreated":5,"permissionsSkipped":1,"rolesCreated":2,"rolesSkipped":0}`。

权限清单用于把一个软件的权限与角色配置导出为 JSON，再导入到其他部门或其他环境：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/manifest?applicationId=3[&departmentId=1]` | 导出；省略 `departmentId` 时导出该软件有配置的全部部门；需要读权限 |
| POST | `/api/admin/manifest/import?applicationId=3` | 导入；需要写权限 |

清单格式（导出结果可直接导入；修改 `departments[].code` 即可导入到另一个部门）：

```json
{"version":1,"application":{"code":"oa","name":"OA"},"exportedAt":"...",
 "departments":[{"code":"rd-center","name":"研发中心",
   "permissions":[{"code":"order:read","name":"查看订单","resource":"order","action":"read"}],
   "roles":[{"code":"reader","name":"阅读者","description":"","enabled":true,"permissions":["order:read"]}]}]}
```

导入在一个事务内按编码新建或更新权限与角色，角色的权限集合按清单整体替换，`enabled` 省略视为启用；清单中没有的权限、角色和全部用户分配保持不变，**不会删除**任何配置。部门编码必须已存在，角色引用的权限编码必须在清单或目标域中存在，否则整体失败。响应为 `{"permissionsCreated":..,"permissionsUpdated":..,"rolesCreated":..,"rolesUpdated":..}`。导入的变更在下一次校验时生效。

### 5.7 审计与哈希链

`GET /api/admin/audit` 分页查询，参数：`departmentId`、`applicationId`、`actor`、`action`、`keyword`（对象、详情或操作人）、`from`、`to`（`YYYY-MM-DD`，含当天）、`page`、`size`。每条记录含时间、操作人、动作、对象、域、详情和来源 IP。软件管理员只能看到其被授权软件的记录。

`GET /api/admin/audit/export` 接受相同筛选参数，在一次数据库快照中读取最新的最多 5000 条，返回 `items/total/page/size`。不使用逐页偏移拼接，导出期间新写入的记录不会导致重复或遗漏；`total` 超过 `items` 数量时应缩小筛选范围。权限清单导出同样在一次只读事务快照中读取权限、角色与关联。

业务 API 写入的审计操作人为 `app:<软件 ID>`。审计不记录密码、API Key、Webhook 密钥或 token 原文，也不记录每次查询和校验。默认保留 180 天（`AUDIT_RETENTION_DAYS`），每天 03:30 分批清理。

审计记录写入约 10 秒后由后台任务封存为 SHA-256 哈希链（每条记录的哈希包含上一条的哈希）。封存按顺序号 `seal_seq` 排列，而不是按记录 ID：晚提交的事务即使 ID 较小，也接在链尾。`GET /api/admin/audit/verify` 先封存尚未封存的记录，再重新计算并校验：参数 `fromSeq`（起始顺序号，省略为从头）、`limit`（默认 5000，最大 50000），任意管理员可调用。响应：

```json
{"ok":true,"problem":null,"checked":120,"firstSeq":1,"lastSeq":120,"firstId":1,"lastId":121,"brokenAt":null,"brokenSeq":null,"complete":true,"nextSeq":null,"seal":{...}}
```

`problem` 为第一处问题：`LINK_BROKEN`（与上一条衔接不上，通常是中间记录被删除）、`MODIFIED`（记录内容被修改）、`TAIL_MISSING`（链尾与封存表记录的不一致，最新的记录被删除）；`brokenAt`、`brokenSeq` 为出问题记录的 ID 与顺序号。一次未校验到链尾时 `complete` 为 false，用 `nextSeq` 作为下一次的 `fromSeq` 继续。按保留期清理旧审计时，最后一条被删除记录的哈希会保存为锚点，剩余的第一条与锚点衔接，因此清理不会被报告为断裂。V5 之后封存的记录使用带长度前缀、包含顺序号的规范化形式，文本在字段之间移动也会被发现；更早的记录按原形式校验。哈希链能发现记录被修改或删除；但它与数据保存在同一个数据库中，拥有数据库写权限的人可以重算整条链，因此不能代替导出到 SIEM 或 WORM 存储的外部存证。

### 5.8 管理员账号（需要 `account.manage`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/accounts` | 账号列表：角色、启用状态、锁定时间、最近登录、软件范围（`allApplications`、`applicationIds`）、是否启用两步验证 |
| POST | `/api/admin/accounts` | `{"username":"ops.zhang","displayName":"张工","role":"APP_ADMIN","password":"12–100 个字符","enabled":true,"allApplications":false,"applicationIds":[3]}`；`role` 为任一管理员角色编码 |
| PUT | `/api/admin/accounts/{username}` | `{"displayName":"","role":"ADMIN","enabled":true,"allApplications":true}` |
| PUT | `/api/admin/accounts/{username}/password` | `{"password":"12–100 个字符"}`，重置后该账号全部会话下线 |
| POST | `/api/admin/accounts/{username}/unlock` | 解除登录锁定 |
| POST | `/api/admin/accounts/{username}/mfa/reset` | 重置两步验证（设备丢失时使用），之后该账号仅凭密码登录，直到重新绑定 |
| DELETE | `/api/admin/accounts/{username}` | 删除账号及其全部会话 |

`allApplications` 为 `false` 时账号只能管理 `applicationIds` 中的软件；省略 `allApplications` 时，提供 `applicationIds` 表示限定为这些软件，不提供表示全部软件。角色不存在返回 400。不能修改自己的角色、停用或删除自己；平台至少保留一个启用的超级管理员。修改角色或停用账号后，该账号的全部会话立即下线；修改软件范围在下一次请求生效。账号名为 3–100 位字母、数字或 `_ . @ -`，不能以 `.` 开头；路径中直接使用账号名，`@` 与 `.` 不需要转义。密码规则与会话失效规则见第 5.1 节。

### 5.9 审批中心

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/access-requests` | 分页查询；参数 `applicationId`、`status`（`PENDING`/`APPROVED`/`REJECTED`/`CANCELLED`）、`keyword`、`page`、`size`；软件管理员只看到被授权软件的申请 |
| POST | `/api/admin/access-requests/{id}/approve` | `{"durationDays":7,"comment":"","permanent":false}`；`permanent` 为 true 时永久授权（此时不能再传 `durationDays`，否则 400）；否则 `durationDays`（1–3650）省略时沿用申请中的天数，申请也未指定则永久授权 |
| POST | `/api/admin/access-requests/{id}/reject` | `{"comment":"理由"}` |

审批要求对申请所属软件有写权限。只能处理 `PENDING` 的申请；角色已停用或用户已不在该部门时批准返回 409。批准会写入（或刷新）该用户的角色分配并记录授予人和到期时间，使该用户在该域的上下文失效，并发出 `access.approved` 事件；驳回发出 `access.rejected` 事件（含驳回理由）。列表中的申请保存提交时的姓名、角色和部门名称；对应记录被删除后 `userId`、`roleId`、`departmentId` 为 null，`roleEnabled` 为 null 表示角色已删除。

### 5.10 权限复核

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET、POST | `/api/admin/reviews` | 列表（参数 `applicationId`、`status` 为 `OPEN`/`CLOSED`）或发起复核：`{"name":"2026 Q4 OA 复核","applicationId":3,"departmentId":1,"dueDays":14}`，`departmentId` 省略表示该软件全部部门，`dueDays` 可选（1–365） |
| GET | `/api/admin/reviews/{id}` | 复核详情，含条目总数与 `pending`、`kept`、`revoked`、`skipped` 统计 |
| GET | `/api/admin/reviews/{id}/items` | 分页列出快照条目；参数 `decision`（`PENDING`/`KEEP`/`REVOKE`）；关闭后每项的 `applied` 表示已处理，`skipped` 表示未回收（见下文） |
| PUT | `/api/admin/reviews/{id}/items/{itemId}` | `{"decision":"REVOKE","comment":"已转岗"}`，`decision` 为 `KEEP` 或 `REVOKE` |
| POST | `/api/admin/reviews/{id}/close` | 关闭复核并执行回收 |

发起时对当前仍有效（未到期）的角色分配拍快照，之后新增的分配不在本次复核内。复核关闭前可以随时修改条目的决定（以最后一次为准），关闭后再提交返回 409。关闭时回收全部标记为 `REVOKE` 的分配，并使相关用户在对应域的上下文失效；**未决定的条目视为保留**。每个条目记录的是快照时的那一次分配：若该分配在复核期间被移除、续期、修改期限或重新授予，关闭时不会回收新的分配，该条目标记为 `skipped`，复核摘要中注明数量。条目保存快照时的用户、角色和部门名称，之后删除这些记录不影响复核历史（对应 ID 变为 null）。实际回收了授权时发出 `review.closed` 事件。复核接口要求对该软件有读（查询）或写（发起、决定、关闭）权限。

### 5.11 Webhook 管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET、POST | `/api/admin/applications/{id}/webhooks` | 列表（含待投递数、失败数、最近成功时间，不含密钥）或登记 |
| PUT、DELETE | `/api/admin/webhooks/{id}` | 修改或删除 |
| GET | `/api/admin/webhooks/{id}/deliveries` | 分页查看投递记录：事件、状态（`PENDING`/`SENDING`/`DELIVERED`/`FAILED`）、尝试次数、下次尝试时间、最后的 HTTP 状态和错误 |
| POST | `/api/admin/webhooks/{id}/test` | 投递一条 `webhook.test` 事件；Webhook 停用时返回 409 |

登记与修改的请求体：

```json
{"name":"OA 会话清理","url":"https://oa.example.internal/hooks/melonhub","events":["user.disabled","user.roles.changed"],"enabled":true,"secret":"可选，16-100 位可打印 ASCII"}
```

`url` 必须以 `http://` 或 `https://` 开头；`events` 为第 3.6 节中的事件名或 `*`（全部），未知事件名返回 400。登记时省略 `secret` 会自动生成，响应 `{"id":..,"secret":".."}` 中的密钥只返回这一次；修改时传入 `secret` 表示更换签名密钥，省略则保持不变。写操作要求对该软件有写权限。投递记录默认保留 30 天（`WEBHOOK_RETENTION_DAYS`），待投递的记录不会被清理。

### 5.12 全局搜索与系统状态

- `GET /api/admin/search?q=关键字`：按名称、编码、外部标识或邮箱搜索用户、部门、软件和角色，每类最多 5 条；软件和角色按调用者可见的软件过滤。`q` 为空或超过 64 个字符时返回空结果。
- `GET /api/admin/system/status`：系统状态看板，返回版本、运行时长、Java 版本、缓存状态、数据库状态（`UP`/`DOWN`）与延迟，以及数据规模（`counts`）、待审批申请数（`pendingRequests`）、进行中的复核数（`openReviews`）、Webhook 待投递数（`webhookBacklog`）、7 天内到期的授权数（`expiringIn7Days`）和审计链封存状态（`audit`）。响应总是包含全部字段：数据库不可用或读取中途失败时，`database` 为 `DOWN`，需要读库的字段全部为 null，不会只返回其中一部分。

### 5.13 管理员角色

| 方法 | 路径 | 所需功能 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/admin/admin-functions` | `account.manage` 或 `role.manage` | 功能清单：`code`、`name`、`category` |
| GET | `/api/admin/admin-roles` | `account.manage` 或 `role.manage` | 角色列表：`code`、`name`、`description`、`builtin`、`functions`、`accountCount`、`createdAt` |
| POST | `/api/admin/admin-roles` | `role.manage` | `{"code":"HELPDESK","name":"服务台","description":"","functions":["org.view","domain.view","domain.assign"]}`；编码为 2–32 位大写字母、数字或下划线 |
| PUT | `/api/admin/admin-roles/{code}` | `role.manage` | `{"name":"服务台","description":"","functions":[...]}`，整体替换功能 |
| DELETE | `/api/admin/admin-roles/{code}` | `role.manage` | 仍有账号使用时返回 409 并给出账号数 |

内置角色不能修改或删除（400）。只能授予操作者自己拥有的功能，否则返回 403；未知功能编码返回 400。增删改记入审计（`ADMIN_ROLE_CREATED/UPDATED/DELETED`）。`/api/auth/login` 与 `/api/auth/me` 返回 `role`、`roleName`、`functions`、`allApplications`、`applicationIds`，控制台据此显示菜单和按钮。

## 6. 错误约定

错误体统一为：

```json
{"code":"CONTEXT_INVALID","message":"中文说明","requestId":"..."}
```

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 字段缺失、类型错误、长度或格式不合法 |
| 400 | INVALID_PATH | 请求路径不是规范形式 |
| 400 | BAD_CREDENTIALS | 修改密码或停用两步验证时当前密码不正确 |
| 400 | MFA_INVALID | 确认绑定时动态口令不正确；停用两步验证时动态口令不正确或已被使用 |
| 401 | UNAUTHORIZED | 管理员会话或 API Key 缺失、无效、过期或已吊销 |
| 401 | BAD_CREDENTIALS | 登录用户名或密码错误；或登录过程中账号凭据已变更 |
| 401 | MFA_REQUIRED | 账号已启用两步验证，登录时未提供动态口令 |
| 401 | MFA_INVALID | 登录时动态口令不正确或已被使用 |
| 401 | CONTEXT_INVALID | 上下文过期、注销、已因变更失效或不属于当前软件 |
| 403 | FORBIDDEN | 越权、跨域关联、非部门成员、用户或软件停用、激活了未分配的角色 |
| 403 | ACCOUNT_LOCKED | 管理员账号因连续登录失败处于锁定期 |
| 403 | ACCOUNT_DISABLED | 管理员账号已停用 |
| 404 | NOT_FOUND | 记录、隔离域或接口不存在 |
| 405 | METHOD_NOT_ALLOWED | 接口不支持该请求方法 |
| 409 | DUPLICATE | 编码或标识重复 |
| 409 | DEPARTMENT_MISMATCH | 创建上下文时传了 `switchDepartment:false`，而用户当前部门不是目标部门 |
| 409 | CONFLICT | 删除时仍有依赖（`message` 说明剩余内容）、密钥数量已达上限、申请或复核已被处理等冲突 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 请求体不是 `application/json` |
| 429 | RATE_LIMITED | 调用频率或登录尝试超过限制，带 `Retry-After` |
| 500 | INTERNAL_ERROR | 未预期的服务错误，请提供 `requestId` 联系管理员 |
| 503 | SERVICE_BUSY | 数据库连接池耗尽、锁等待超时、数据库暂不可用，或角色成员被同时修改且重试仍冲突，带 `Retry-After` |

权限不足一律按拒绝处理，不能把网络错误或未知权限视为允许。

## 7. 监控端点

监控端口 8081 只监听 `127.0.0.1`（`MANAGEMENT_PORT` 可调整），提供：

- `/actuator/health`：数据库、Redis、磁盘等组件的详细健康状态。
- `/actuator/metrics`、`/actuator/prometheus`：标准 HTTP、JVM、Hikari 连接池、Lettuce（Redis 客户端）指标。
- `melonhub.cache.lookups`：权限缓存查找次数，标签 `kind`（`context` 或 `apikey`）和 `result`（`hit`、`miss`、`none`、`bypass`）。`bypass` 持续增长说明 Redis 不可用，校验正在走数据库。
