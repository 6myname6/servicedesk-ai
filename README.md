# 智维工单（ServiceDesk AI）

> 企业售后 / 设备报修工单系统 + AI 坐席
> 让报修、派单、处理、知识沉淀、SLA 考核跑在一条线上，AI 只做增强，不替代人。

## 产品定位

- **业务**：中小企业设备售后、物业报修、IT 运维
- **角色**：报修人、工程师、主管、管理员
- **闭环**：报修 → AI 分类定级 → 派单 → 接单 → 处理（知识推荐）→ 解决 → 回访
- **AI**：结构化建单、RAG 知识推荐、Agent 工具调用、NL2SQL 报表、工单摘要/质检
- **差异化**：AI 嵌进工单生命周期；写操作必须人工确认；权限在查询层；NL2SQL 只读 + AST 校验
## 文档索引

| 文档 | 内容 |
| --- | --- |
| [产品文档](backend/src/main/resources/docs/产品文档.md) | 背景、角色权限、31 条功能需求、页面交互、状态机与 SLA、非功能需求、验收 |
| [ER 图](backend/src/main/resources/docs/ER图.md) | 14 张表的 Mermaid ER 图、字段字典、索引、枚举 |
| [接口文档](backend/src/main/resources/docs/接口文档.md) | 通用约定、9 个模块 51 个接口、请求/响应样例、附录数据结构 |


## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.5.16 + MyBatis-Plus + MySQL 8 + Redis 7 + LangChain4j |
| 数据 | MySQL 8（业务 13 张表）+ PostgreSQL 16（`kb_chunk` 向量表） |
| 前端 | Vue 3 + Vite + Pinia + Element Plus + ECharts |
| 鉴权 | JWT + 拦截器 + ThreadLocal 用户上下文 |
| 向量 | PostgreSQL 16 + pgvector（`vector(1024)`，HNSW 召回） |
| 对象存储 | 阿里云 OSS（附件 / 知识库文档；`FileStorageService` 接口可替换 MinIO/S3） |
| 部署 | Docker Compose |

## 命名约定

- 产品名：智维工单（ServiceDesk AI）
- 仓库/目录：`servicedesk-ai`
- Java 包名：`com.servicedesk`
- 数据库：MySQL `servicedesk_ai`、PostgreSQL `servicedesk_kb`（向量）
- API 基址：`/api`

## 文档维护规则

- 三份文档共享同一套角色、状态、枚举、表名、接口路径；改一处必须同步另外两处。
- 指标未实测的，一律写 `【】` 占位，不编数字。
- 接口文档的响应结构以 `{ code, message, data }` 为准，`code=200` 表示成功。
