# 智维工单 · 前端

Vue 3 + Vite + Pinia + Vue Router + Element Plus，对齐 `design/` 效果图与 `开发文档.md` 第 6 章页面清单。

当前状态分两类：

- **已接真实接口**：登录、工单列表、建单、客户与设备、用户管理、团队管理。
- **页面原型**：工作台、工单详情、知识库、运营报表、注册、Agent 抽屉。后端对应接口未就绪，数据来自 `src/mock/prototype.js`，页面统一挂「原型示例数据」徽标；接口就绪后删除 mock 文件并改为 `src/api/` 调用。

## 1. 本地开发

前提：后端已在 `http://127.0.0.1:8080` 启动（context-path 为 `/api`）。

```bash
cd frontend
npm install
npm run dev          # http://127.0.0.1:5173
```

开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`（见 `vite.config.js`），因此前端代码里只写相对路径 `/api`，不出现后端地址。


### 演示账号

`backend/sql/seed/seed_demo_data.sql` 的账号，统一口令 `Sd123456`：

| 账号 | 角色 |
| --- | --- |
| `admin` | 管理员（可见全部菜单） |
| `sup1` / `sup2` | 主管（客户与设备、知识库、报表） |
| `eng1` ~ `eng3` | 工程师（工单列表与建单） |
| `rep1` / `rep2` | 报修人（只看自己的工单） |

## 2. 页面进度对照

| 路由 | 页面 | 状态 | 数据来源 / 依赖接口 |
| --- | --- | --- | --- |
| `/login` | P-01 登录 | 已实现 | `POST /api/auth/login` |
| `/register` | P-12 注册 | 原型 | 注册接口未实现，提交后跳登录并提示 |
| `/dashboard` | P-02 工作台 | 原型 | R-02 工作台聚合未实现 |
| `/tickets` | P-03 工单列表 | 已实现 | `GET /api/tickets` |
| `/tickets/new` | P-05 建单 | 已实现 | `POST /api/tickets`、`POST /api/files` |
| `/tickets/:id` | P-04 工单详情 | 原型 | `GET /api/tickets/{id}` 已就绪，前端尚未切换；派单接口已就绪 |
| `/kb` | P-06 知识库列表 | 原型 | K-01 / K-02 未实现 |
| `/kb/new`、`/kb/:id` | P-07 知识文章编辑 | 原型 | 抽屉形式，路由决定开关状态 |
| `/reports` | P-08 运营报表 | 原型 | R-01 / AI-04 未实现 |
| `/admin/assets` | P-11 客户与设备 | 已实现 | `/api/admin/customers`、`/api/admin/devices` |
| `/admin/users` | P-09 用户管理 | 已实现 | `/api/admin/users` |
| `/admin/teams` | P-10 团队管理 | 已实现 | `/api/admin/teams` |
| `/403` | 无权限 | 已实现 | — |
| 全局 | C-01 Agent 抽屉 | 原型 | AI-03 未实现，写操作仅做确认演示 |

登录页的演示账号面板、顶栏角色菜单、路由守卫（`meta.roles`）与 401 自动回登录均已接通。

### 原型页面的交互边界

- 工单详情的「原型：状态预览」下拉仅用于评审不同状态下的操作区，真实接口接入后移除。
- 知识库的保存、重建索引、上传在原型中为本地模拟，不落库。
- 报表的 NL2SQL 与 Agent 对话返回固定演示结果，不调用模型。
- 注册原型只做校验，不写入本地登录态，避免假 token 影响真实接口。

### 已知边界（不是前端可以绕过的）

- **客户/设备下拉仅主管与管理员可用**：后端客户档案查询限定 `SUPERVISOR`/`ADMIN`，报修人与工程师建单时改为填写客户 ID（演示数据 1-30）。
- **附件上传依赖 OSS**：`storage.oss.access-key-id/secret` 未配置时后端返回 503，页面会提示失败，但不阻塞建单。
- **派单的工程师候选列表**：后端尚无「按团队查可派工程师」接口，详情页派单弹窗待接口就绪后接入；管理接口 `/admin/users` 仅管理员可调。
- **状态流转与处理记录**：F-02 / F-03 后端未实现，详情页操作仅做交互演示。

## 3. 构建

```bash
cd frontend
npm run build        # 输出 dist/
```

产物结构：`index.html` + `assets/`（`vendor-vue`、`vendor-element` 单独拆包，便于 nginx 长期缓存）。

## 4. 部署到 nginx

仓库根目录的 `deploy/` 是一套**自包含的本机 nginx**：不需要你先装 nginx，也不用去找系统里的 nginx 目录。

```
deploy/
├── nginx.conf            # 唯一配置事实源（80 端口、/api 反代、SPA 回落、静态缓存）
├── start-nginx.ps1       # 启动 / 重载 nginx
├── stop-nginx.ps1        # 停止 nginx
├── deploy-frontend.ps1   # 构建 + 同步产物 + 重载
└── nginx/                # 运行时（不入库）：nginx.exe、conf、logs、html\servicedesk-ai
```

### 4.1 首次准备（只做一次）

`nginx/` 里的二进制不入库，从机器上任意一个 nginx 发行版复制过去即可（本项目已从本机已有的 nginx 复制完成）：

```powershell
copy <任意 nginx 目录>\nginx.exe d:\servicedesk-ai\deploy\nginx\
xcopy <任意 nginx 目录>\conf d:\servicedesk-ai\deploy\nginx\conf\ /E /I /Y
```

然后启动后端（必须监听 `127.0.0.1:8080`，context-path `/api`），例如 IDEA 里运行 `ServiceDeskApplication` 或：

```bash
cd backend && mvn spring-boot:run
```

### 4.2 发布与启动

```powershell
# 构建前端 + 拷贝到 deploy\nginx\html\servicedesk-ai + 重载（未启动时只提示）
powershell -ExecutionPolicy Bypass -File deploy\deploy-frontend.ps1

# 启动（已在运行则 reload），随后访问 http://localhost/
powershell -ExecutionPolicy Bypass -File deploy\start-nginx.ps1

# 停止
powershell -ExecutionPolicy Bypass -File deploy\stop-nginx.ps1
```

日志在 `deploy/nginx/logs/`（`error.log`、`access.log`）。

### 4.3 换到 Linux / 别的 nginx

配置只有 `deploy/nginx.conf` 一个文件：拷到 `/etc/nginx/conf.d/servicedesk-ai.conf` 时把 `root html/servicedesk-ai;` 改成实际目录（如 `/usr/share/nginx/html/servicedesk-ai`），并去掉 `deploy/nginx/` 的 `-p` 前缀依赖即可。

要点（已写在配置文件注释里）：

- `location /api/` 的 `proxy_pass` **不带 URI**（`http://127.0.0.1:8080`），否则 `/api` 前缀会被吃掉；
- 前端是 history 路由，`try_files $uri $uri/ /index.html` 必须有，否则刷新 `/tickets` 会 404；
- `client_max_body_size 25m` 要大于后端 `spring.servlet.multipart.max-request-size`；
- 登录限流按客户端 IP：若要让后端识别真实 IP，需同时设置后端 `TRUST_PROXY=true`（仅可信代理后开启）。

### 4.4 验证

```powershell
curl.exe -I http://localhost/          # 200，返回 index.html
curl.exe -I http://localhost/tickets   # 200，history 回落
curl.exe -s http://localhost/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"Sd123456\"}"
```

## 5. 目录结构

```
frontend/
├── nginx/servicedesk-ai.conf   # 站点配置（含 /api 反向代理与缓存策略）
├── src/
│   ├── api/                    # axios 实例与各模块接口封装
│   ├── components/             # 状态/紧急度/SLA 标签、趋势图、Agent 抽屉、原型徽标
│   ├── mock/                   # 原型示例数据（接口就绪后删除）
│   ├── layouts/                # 侧边栏 + 顶栏布局
│   ├── router/                 # 路由表与角色守卫
│   ├── stores/                 # 登录态
│   ├── styles/                 # 设计令牌与 Element Plus 主题覆盖
│   ├── utils/                  # 字典、时间与 SLA 格式化
│   └── views/                  # 页面
└── vite.config.js
```
