# 本地数据库部署方案（Docker）

本项目采用 **PostgreSQL 16 + PgVector** 作为唯一数据库（生产级向量库，支撑 RAG 检索与用户隔离），并配套 **Redis**（缓存 / 会话）。

> ⚠️ 开发与生产**一律**走 `pg` profile（`application.yml` 默认 `spring.profiles.active=pg`）；
> **H2 已退出开发/生产路径**，仅作为测试期的装配替身（`pom.xml` 中为 `test` scope，见
> `src/test/resources/application-test.yml`）。本文写于 V2.0 时期，库名/用户名以 `docker-compose.yml` 为准（`dcdr_agent`）。

---

## 〇、脚本清单（2026-10-15 合并后）

| 文件 | 内容 | 说明 |
|---|---|---|
| `v2.0_schema.sql` | 基础表（users / knowledge_bases / documents / document_chunks / agent_definitions / workflow_definitions / sessions / projects / messages / generations / audit_logs 等）+ pgvector 扩展 | initdb 的 `01_`，也是升级的起点 |
| `v3.0_schema.sql` | routing_logs / webhook_endpoints / workflow_executions → workflow_versions / llm_usage_logs / template_shares → tenants → llm_call_logs | `02_`；由原 beta / rc / llm_trace 三个分脚本合并，段落保留来源标注 |
| `v3.1_schema.sql` | projects.user_id → agent_definition_versions → 长期记忆 → 技能与专家维度 → message_feedback → 混合检索索引（pg_trgm + trigram GIN） | `03_`；由原 5 个功能分脚本合并（2026-10-15 又并入原 v3.2 的混合检索索引 —— 该能力已随 v3.1 交付且随仓默认开启），**段落顺序即依赖顺序**（f44 建的表会被 expert_skill 段 ALTER） |
| `v3.2_schema.sql` | llm_call_logs.failed_step / error_message（调用链失败定位） | `04_`；纯增量，全部 `IF NOT EXISTS`，仅补列与 COMMENT |

> **升级路径**：从旧库升级 = 按版本逐个执行（或一条 psql 带多个 `-f`，见第九节）；
> 全新部署 = `docker compose up -d` 自动完成。应用侧 `ddl-auto=update` 始终兜底补齐表与列。

---

## 一、前置条件

- 已安装 **Docker Desktop**（Windows / macOS）或 **Docker Engine + docker-compose**（Linux）
- 验证安装：
  ```bash
  docker --version
  docker compose version
  ```
- 端口未被占用：`5432`（PostgreSQL）、`6379`（Redis）

---

## 二、一键启动（推荐）

仓库根目录已提供 `docker-compose.yml`，在**项目根目录**执行：

```bash
# 后台启动 PostgreSQL + Redis，并自动执行建库脚本
docker compose up -d
```

启动后自动完成：
1. 拉取 `pgvector/pgvector:pg16` 与 `redis:7-alpine` 镜像
2. 创建数据库 `dcdr_agent`、用户/密码 `dcdr_agent` / `dcdr_agent`
3. 挂载 `docs/sql/` 下 v2.0 / v3.0 / v3.1 / v3.2 四个脚本到初始化目录（`01_~04_` 前缀排序），
   **首次建库时自动全量执行（含 pgvector 扩展）** —— 全新部署无需手工执行任何 SQL
4. 数据持久化到名为 `pgdata` 的 Docker 卷

### 查看状态与日志

```bash
docker compose ps
docker compose logs -f postgres
```

### 健康检查

```bash
docker compose ps          # STATUS 显示 healthy 即就绪
# 或手动探活
docker exec dcdr-agent-pg pg_isready -U dcdr_agent -d dcdr_agent
docker exec dcdr-agent-redis redis-cli ping   # 预期返回 PONG
```

---

## 三、连接信息

| 项目 | 值 |
|------|-----|
| JDBC URL | `jdbc:postgresql://localhost:5432/java_agent` |
| 用户名 | `java_agent` |
| 密码 | `java_agent` |
| PostgreSQL 端口 | `5432` |
| Redis 端口 | `6379` |

应用侧对应配置在 `src/main/resources/application-pg.yml`。

---

## 四、手动验证数据库

### 连 PostgreSQL 执行 SQL
```bash
docker exec -it dcdr-agent-pg psql -U dcdr_agent -d dcdr_agent -c "\dt"
```
应能看到：`users` `knowledge_bases` `documents` `document_chunks` `agent_definitions` `audit_logs` `sessions`（含 `user_id` 列）。

### 确认 pgvector 扩展与向量表
```bash
docker exec -it dcdr-agent-pg psql -U dcdr_agent -d dcdr_agent -c "SELECT extname FROM pg_extension WHERE extname='vector';"
docker exec -it dcdr-agent-pg psql -U dcdr_agent -d dcdr_agent -c "\d document_chunks"
```

### 连 Redis
```bash
docker exec -it dcdr-agent-redis redis-cli ping
```

---

## 五、启动应用（pg profile）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=pg
# 或打包后：
java -jar target/dcdr-agent-<version>.jar --spring.profiles.active=pg   # 产物名随 pom 的 version 变化
```

> `application-pg.yml` 中 `ddl-auto: update`：表结构的**规范来源**仍是 `docs/sql/*.sql`，但 JPA 会在应用启动时自动补齐**新增**的表 / 列 / 索引（只做 ADD，不删列、不改已存在列的类型）。
> 脚本需要保留并执行一次的场景：补齐 JPA 不生成的部分（`COMMENT`、部分索引 `WHERE`、`DESC` 排序索引）。脚本均为 `IF NOT EXISTS` 写法，可重复执行。

---

## 六、重新初始化 / 重置数据

```bash
# 停止并删除容器与数据卷（会清空所有数据！）
docker compose down -v
# 重新拉起（会重新执行建库脚本）
docker compose up -d
```

仅重建容器保留数据：
```bash
docker compose down        # 不加 -v，pgdata 卷保留
docker compose up -d
```

---

## 七、修改配置（常见调整）

编辑根目录 `docker-compose.yml`：

- **改密码**：同时修改 `POSTGRES_PASSWORD` 与 `application-pg.yml` 的 `spring.datasource.password`
- **改库名**：修改 `POSTGRES_DB`，并同步 `application-pg.yml` 的 URL
- **仅本机访问**：端口映射 `"5432:5432"` 已限定本机；如需局域网访问保持不变即可
- **修改 schema**：编辑 `docs/sql/` 下对应版本的脚本后执行 `docker compose down -v && docker compose up -d` 重新建库（initdb 只在数据卷为空时执行）

---

## 八、常见问题

**1. 端口被占用**
```bash
# Windows 查看占用 5432 的进程
netstat -ano | findstr :5432
```
修改 `docker-compose.yml` 的端口映射（如 `"5433:5432"`），并同步应用配置。

**2. 镜像拉取慢 / 失败**
配置国内镜像源（Docker Desktop → Settings → Docker Engine 添加 registry-mirrors），或手动 `docker pull pgvector/pgvector:pg16`。

**3. 容器一直 unhealthy**
查看日志 `docker compose logs postgres`。常见原因：数据卷来自旧版本导致扩展冲突，执行 `docker compose down -v && docker compose up -d` 重置。

**4. WSL2 磁盘空间不足**
`docker system prune -a` 清理无用镜像，或迁移 Docker 数据到非系统盘。

---

## 九、无 Docker 的备选方案（Windows 本地安装）

若环境无法使用 Docker，可手动安装：

1. 安装 PostgreSQL 16：https://www.postgresql.org/download/windows/
2. 安装 pgvector 扩展：
   - 用已编译的 Windows 包：https://github.com/pgvector/pgvector/releases
   - 将 `vector.dll` 放到 `PostgreSQL\16\lib`，`vector.control` / `vector--*sql` 放到 `PostgreSQL\16\share\extension`
3. 建库并授权：
   ```sql
   CREATE DATABASE dcdr_agent;
   \c dcdr_agent
   CREATE USER dcdr_agent WITH PASSWORD 'dcdr_agent';
   GRANT ALL PRIVILEGES ON DATABASE dcdr_agent TO dcdr_agent;
   ```
4. 执行 schema（一条命令、三个文件按序；全部幂等可重复执行）：
   ```bash
   psql -U dcdr_agent -d dcdr_agent \
     -f docs/sql/v2.0_schema.sql -f docs/sql/v3.0_schema.sql \
     -f docs/sql/v3.1_schema.sql -f docs/sql/v3.2_schema.sql
   ```
5. Redis 可用 Windows 版或 WSL 内 `apt install redis-server` 启动。
