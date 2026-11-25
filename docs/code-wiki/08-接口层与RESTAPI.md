# 08 · 接口层与 REST API

## 1. 通用约定

| 项 | 约定 |
| --- | --- |
| 基址 | `http://localhost:8080/api`（`server.servlet.context-path=/api`） |
| Controller 路径 | 不含 `/api`；本文表格写对外完整路径 |
| 认证 | `Authorization: Bearer {token}`；登录接口免鉴权 |
| 响应体 | `{ code, message, data }`；`code = 200` 表示成功，与 HTTP 状态一致 |
| 分页 | 请求 `page`（从 1 开始）/ `size`（默认 10，最大 100）；响应 `{records,total,curPage,pageSize,pages}` |
| 时间 | `yyyy-MM-dd'T'HH:mm:ss`，时区 `Asia/Shanghai` |
| ID | BIGINT 自增；当前量级在 JS 安全整数范围内，Jackson 按数字返回 |
| 枚举 | 大写字符串，非法值 400 |
| 错误 | 资源不存在与越权统一 404 |
| 上传 | `multipart/form-data`；单文件 20MB、请求 25MB（容器上限） |
| 文档 | Swagger UI `/api/swagger-ui.html`；OpenAPI `/api/v3/api-docs` |

### 1.1 统一响应示例

```json
{ "code": 200, "message": "成功", "data": { } }
```

### 1.2 错误码

| code | 含义 | 典型场景 |
| --- | --- | --- |
| 200 | 成功 | — |
| 400 | 参数错误 | 校验失败、枚举非法、业务前置条件不满足 |
| 401 | 未认证 | 无 token、过期、伪造、已登出、账号停用、密码错误 |
| 403 | 无权限 | 接口角色不足、账号停用（登录时）、迁移角色不足 |
| 404 | 不存在或越权 | 工单 / 用户 / 团队 / 客户 / 设备 / 附件不存在 |
| 409 | 冲突 | 版本冲突、状态不允许、唯一键冲突、重复评价 |
| 413 | 文件过大 | 超过 10MB / 20MB 或容器上限 |
| 429 | 限流 | 登录失败次数过多 |
| 500 | 服务端错误 | 未捕获异常、附件元数据写库失败 |
| 503 | 依赖不可用 | Redis / OSS 不可用、SLA 策略缺失、工单号耗尽 |

### 1.3 鉴权失败响应示例

```json
{ "code": 401, "message": "登录已过期，请重新登录", "data": null }
```

## 2. 接口总览（34 个）

| 模块 | 数量 | 说明 |
| --- | --- | --- |
| 认证 | 4 | 登录、登出、当前用户、改密 |
| 工单 | 11 | 建单、列表、详情、编辑、取消、评价、派单、状态迁移、处理记录、时间线、SLA |
| 用户管理 | 4 | 分页、新增、修改、启停 |
| 团队管理 | 3 | 列表、新增、修改 |
| 客户管理 | 3 | 分页、新增、修改 |
| 设备管理 | 3 | 分页、新增、修改 |
| SLA 策略 | 4 | 列表、新增、修改、删除 / 停用 |
| 附件 | 2 | 上传、删除 |

## 3. 认证模块 `/auth`

### 3.1 登录

```http
POST /api/auth/login
Content-Type: application/json
```

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 3-32 位 |
| `password` | string | 是 | 8-32 位明文 |

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 604800,
    "user": {
      "id": 1, "username": "admin", "displayName": "系统管理员",
      "role": "ADMIN", "teamId": null, "teamName": null,
      "customerId": null, "customerName": null,
      "phone": "13800000001", "email": "admin@servicedesk.local", "status": 1
    }
  }
}
```

服务端顺序：账号锁检查 → IP 锁检查 → 查库 → BCrypt 校验 → 状态校验 → 清账号失败计数 → 签发 token。

### 3.2 登出

```http
POST /api/auth/logout
Authorization: Bearer {token}
```

把当前 token 加入 Redis 黑名单，TTL = 剩余有效期。

### 3.3 当前用户

```http
GET /api/auth/me
```

以数据库为准返回 `UserVO`，不回显 token 中的旧角色 / 团队。

### 3.4 修改密码

```http
PUT /api/auth/password
Content-Type: application/json

{ "oldPassword": "Sd123456", "newPassword": "NewPass123" }
```

只改当前登录用户；旧密码错误或新旧相同 → 400。

## 4. 工单模块 `/tickets`

### 4.1 接口清单

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/tickets` | 登录 | 建单 |
| GET | `/api/tickets` | 登录 | 分页列表（自动应用数据范围） |
| GET | `/api/tickets/{id}` | 登录 | 详情 |
| PUT | `/api/tickets/{id}` | REPORTER / SUPERVISOR / ADMIN | 编辑 |
| POST | `/api/tickets/{id}/cancel` | REPORTER / SUPERVISOR / ADMIN | 取消 |
| POST | `/api/tickets/{id}/feedback` | REPORTER | 评价 |
| POST | `/api/tickets/{id}/assign` | SUPERVISOR / ADMIN | 派单 |
| PATCH | `/api/tickets/{id}/status` | 登录 + 迁移表角色 | 状态迁移 |
| POST | `/api/tickets/{id}/comments` | 登录 | 追加处理记录 |
| GET | `/api/tickets/{id}/events` | 登录 | 时间线（不分页，≤500） |
| GET | `/api/tickets/{id}/sla` | 登录 | SLA 倒计时状态 |

### 4.2 建单

```json
POST /api/tickets
{
  "title": "空压机异响",
  "description": "启动后持续异响，压力不稳",
  "customerId": 1,
  "deviceId": 1,
  "category": "HARDWARE",
  "urgency": "P1",
  "imageUrls": ["https://bucket.oss-cn-beijing.aliyuncs.com/ticket/202611/xxx.jpg"]
}
```

返回 `TicketDetailVO`（结构见 4.4）。错误：客户 / 设备 / 附件不合法 400；SLA 策略缺失 503；工单号连续冲突 503。

### 4.3 列表

```http
GET /api/tickets?status=IN_PROGRESS&urgency=P1&mine=true&page=1&size=10
```

| 参数 | 说明 |
| --- | --- |
| `keyword` | 模糊匹配标题或工单号 |
| `status` / `urgency` / `category` | 传了必须合法，否则 400 |
| `assigneeId` / `teamId` | 精确过滤 |
| `mine` | 我报修或我处理 |
| `escalated` | 响应或解决任一升级过 |
| `page` / `size` | 分页 |

返回：

```json
{
  "code": 200, "message": "成功",
  "data": {
    "records": [{
      "id": 1, "ticketNo": "TK20261115000001", "title": "空压机异响",
      "category": "HARDWARE", "urgency": "P1", "status": "IN_PROGRESS",
      "customerName": "九江精工机械有限公司", "deviceModel": "AC-2000",
      "assigneeName": "张工", "teamName": "华东售后一组",
      "slaRemainingMinutes": 45, "slaBreached": false,
      "createdAt": "2026-11-15T09:30:00"
    }],
    "total": 1, "curPage": 1, "pageSize": 10, "pages": 1
  }
}
```

### 4.4 详情结构 `TicketDetailVO`

```json
{
  "id": 1, "ticketNo": "TK20261115000001", "title": "空压机异响",
  "description": "...", "category": "HARDWARE", "urgency": "P1",
  "status": "IN_PROGRESS",
  "customer": { "id": 1, "name": "九江精工机械有限公司", "phone": "0791-..." },
  "device": { "id": 1, "model": "AC-2000", "serialNo": "SN-2024-001", "warrantyEnd": "2027-01-01" },
  "reporter": { "id": 7, "displayName": "赵报修" },
  "assignee": { "id": 4, "displayName": "张工" },
  "team": { "id": 1, "name": "华东售后一组" },
  "sla": { "...": "见 4.7" },
  "ai": null,
  "attachments": [{ "id": 1, "fileName": "a.jpg", "fileUrl": "https://...", "fileSize": 10240, "mimeType": "image/jpeg", "createdAt": "..." }],
  "createdAt": "2026-11-15T09:30:00",
  "updatedAt": "2026-11-15T10:00:00",
  "version": 3
}
```

`version` 必须原样带回给编辑 / 取消 / 派单 / 状态迁移接口。

### 4.5 编辑

```json
PUT /api/tickets/1
{
  "title": "空压机异响（已定位）",
  "urgency": "P0",
  "deviceId": 1,
  "version": 3
}
```

业务字段均可选；`version` 必填。紧急度变化会以当前时刻重算两个 deadline。

### 4.6 取消 / 评价 / 派单 / 状态迁移 / 处理记录

| 接口 | 请求体 | 说明 |
| --- | --- | --- |
| `POST /{id}/cancel` | `{ "reason": "客户撤回", "version": 3 }` | `RESOLVED` 与终态不可取消 |
| `POST /{id}/feedback` | `{ "score": 5, "comment": "处理很快" }` | 仅 `CLOSED`，一单一评 |
| `POST /{id}/assign` | `{ "assigneeId": 4, "comment": "就近处理", "version": 2 }` | 仅 `NEW`，工程师须本团队 |
| `PATCH /{id}/status` | `{ "targetStatus": "ACCEPTED", "comment": "已联系客户", "version": 3 }` | 五个动作由 `TicketTransition` 判定 |
| `POST /{id}/comments` | `{ "content": "已更换轴承", "internal": false }` | 终态不可写；报修人不能写内部备注 |

### 4.7 时间线与 SLA

```http
GET /api/tickets/1/events
```

```json
[{
  "id": 10, "type": "COMMENT", "fromStatus": null, "toStatus": null,
  "operatorId": 4, "operatorName": "张工", "operatorRole": "ENGINEER",
  "content": "已更换轴承", "internal": false, "metadata": { "internal": false },
  "createdAt": "2026-11-15T11:00:00"
}]
```

```http
GET /api/tickets/1/sla
```

```json
{
  "policyId": 2, "policyName": "P1 标准",
  "responseDeadline": "2026-11-15T10:00:00",
  "resolveDeadline": "2026-11-15T13:30:00",
  "firstResponseAt": "2026-11-15T09:45:00",
  "paused": false, "pausedTotalMinutes": 0,
  "responseRemainingMinutes": -15,
  "resolveRemainingMinutes": 195,
  "responseBreached": false,
  "resolveBreached": false
}
```

`responseBreached` 为 false 是因为已经响应（`firstResponseAt` 非空）；剩余分钟可以为负，只表示倒计时差值。

## 5. 系统管理模块

### 5.1 用户 `/admin/users`（ADMIN）

| 方法 | 路径 | 请求 | 返回 |
| --- | --- | --- | --- |
| GET | `/api/admin/users` | `keyword`、`role`、`status`、`page`、`size` | `PageResult<UserVO>` |
| POST | `/api/admin/users` | `UserCreateRequest` | `UserVO` |
| PUT | `/api/admin/users/{id}` | `UserUpdateRequest` | `UserVO` |
| PATCH | `/api/admin/users/{id}/status` | `{ "status": 1 }` | `null` |

额外规则：团队与客户归属互斥；不能停用自己；不能把自己降级为非管理员；停用会写 Redis 标记即时生效。

### 5.2 团队 `/admin/teams`（ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/teams` | 全量列表，含主管姓名与成员数 |
| POST | `/api/admin/teams` | 名称唯一；主管可为空 |
| PUT | `/api/admin/teams/{id}` | 名称、主管、描述整体覆盖 |

### 5.3 客户 `/admin/customers`（SUPERVISOR / ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/customers` | `keyword` 匹配名称或电话，ID 倒序 |
| POST | `/api/admin/customers` | 名称必填，等级缺省 `NORMAL` |
| PUT | `/api/admin/customers/{id}` | 整体覆盖 |

无删除接口：客户被设备与工单逻辑引用。

### 5.4 设备 `/admin/devices`（SUPERVISOR / ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/devices` | `keyword`（型号 / 序列号）、`customerId`，返回实时 `warrantyStatus` |
| POST | `/api/admin/devices` | 客户存在、序列号唯一、保修到期不早于购买日期 |
| PUT | `/api/admin/devices/{id}` | 整体覆盖；`status` 未传保留原值 |

无删除接口：报废只改状态。

### 5.5 SLA 策略 `/sla-policies`

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/sla-policies` | 登录 | 按 priority 升序 |
| POST | `/api/sla-policies` | ADMIN | 优先级唯一；解决时限 ≥ 响应时限 |
| PUT | `/api/sla-policies/{id}` | ADMIN | 不含优先级 |
| DELETE | `/api/sla-policies/{id}` | ADMIN | 未被引用则物理删除，被引用则停用 |

## 6. 附件模块 `/files`

| 方法 | 路径 | 请求 | 返回 |
| --- | --- | --- | --- |
| POST | `/api/files` | multipart：`file`、`bizType`（`TICKET` / `KB`，缺省 `TICKET`） | `AttachmentVO` |
| DELETE | `/api/files/{id}` | — | `null` |

上传成功只落元数据（`ticket_id` 为 null），建单时通过 `imageUrls` 回填。OSS 未配置时返回 503。

## 7. 文档中存在但代码未实现的接口

以下接口在 `backend/src/main/resources/docs/接口文档.md` 有契约，但仓库中没有对应 Controller / Service，调用会 404：

| 文档章节 | 接口 | 状态 |
| --- | --- | --- |
| 1.4 ~ 1.6 | 邮箱验证码、验证码登录、邮箱注册 | 未实现 |
| 3.5 / 3.6 | 工单私信发送与查询 | 未实现（`ticket_message` 表未建） |
| 6.x | 知识库文章 CRUD、重建索引、文档入库 | 未实现（仅 `kb_chunk` 建表脚本） |
| 7.x | AI 分类、知识推荐、知识问答、Agent 对话 / 确认 / 轨迹 | 未实现 |
| 8.x | 运营概览、NL2SQL 报表 | 未实现 |
| — | 注册 | 前端有原型页，后端无接口 |

## 8. Controller 编写约定

1. 只做参数接收、`@Valid` 校验与 `Result.success` 包装；
2. 不写业务判断，不 try/catch；
3. 角色用 `@RequiredRole`，行级范围与状态交给 Service；
4. 日志用 `debug` 记录入参摘要，不记录正文、备注原文、密码；
5. 新增接口同步更新 `OpenApiConfig` 所在文档与 `docs/code-wiki/08-接口层与RESTAPI.md`。
