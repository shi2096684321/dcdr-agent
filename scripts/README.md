# scripts/ 运维脚本索引

本目录只放**运维动作**，不放开发/构建/测试流程。判断标准：这件事能不能用一条通用命令
（`mvn`、`docker compose`、`npm`）直接做完 —— 能，就不该在这里加脚本。

| 脚本 | 用途 | 什么时候跑 | 前置条件 |
|---|---|---|---|
| `backup-db.ps1` | PostgreSQL 全量备份（pg_dump -Fc），落到宿主机 `data/backup/db/`，并校验备份可读、按天数清理 | **每日 02:00**（计划任务），以及任何"要动数据库"的操作之前 | `dcdr-agent-pg` 容器运行中；无需停应用 |
| `restore-db.sh` | 把 `.dump` 恢复到一个**新库**，并打印核心表行数 | 每季度恢复演练；灾难恢复 | 有可用备份文件；本机/容器内有 `psql`、`pg_restore` |
| `ha-standby-init.ps1` | 在主库创建复制角色 `repl` + 放行 `pg_hba` 复制规则（幂等） | 首次做多实例/主从演练前，一次性 | 主库容器运行中 |

## backup-db.ps1

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1              # 默认保留 14 天
powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1 -RetainDays 30
```

产物：`data/backup/db/dcdr_agent_<yyyyMMdd_HHmmss>.dump` + `data/backup/backup.log`。

**为什么必须"落宿主机"**：PG 数据在命名卷里，物理上位于 Docker VM 的 vhdx。
2026-09-28 Docker Desktop 被重置，vhdx 连同卷一起消失，业务数据全丢——当时容器内的
备份目录也一起没了，等于没有备份。所以备份路径必须是 bind mount 到宿主机的目录。

Linux 宿主没有 PowerShell 时的等效做法写在脚本头部注释里（crontab 一行）。

## restore-db.sh

```bash
./scripts/restore-db.sh ../data/backup/db/dcdr_agent_20261021_020000.dump dcdr_agent_restore
```

行为约束（刻意设计，别改）：

- **目标库已存在则直接退出**，绝不覆盖生产库；确要覆盖请先手动 `DROP DATABASE`。
- 恢复用 `--single-transaction --exit-on-error`，失败即整体回滚，不留半截数据。
- 结束时打印 `users / sessions / knowledge_bases / documents / agent_definitions /
  workflow_definitions / long_term_memories` 的行数，作为演练 checklist 的"数据完整性"证据。

## ha-standby-init.ps1

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ha-standby-init.ps1
```

只在 `docker-compose.ha.yml` 的 `pg-standby` 演练前需要执行一次（备库靠 `pg_basebackup`
从主库拉全量，需要复制角色）。日常开发完全用不到。

## 已删除的脚本（v3.2，2026-10-21）

| 脚本 | 删除理由 |
|---|---|
| `run-tests.ps1` | 纯包装 `mvn test` / `mvn verify`，没有额外逻辑；测试请直接用 `mvn test`（本机秒级、零外部依赖）与 `mvn verify`（含 IT，需 Docker） |
| `backup-db.sh` | 与 `backup-db.ps1` 同一份逻辑的第二实现，且默认库名仍是早期的 `agent`（实际是 `dcdr_agent`），已长期未维护。Linux 用法收敛到 `backup-db.ps1` 头部注释中的 crontab 写法 |

两份逻辑并存 = 双份维护成本 + 迟早有一份过期；保留单一入口是刻意的。
