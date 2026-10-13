#!/bin/sh
# ==============================================================================
# dcdr-agent V3.1 F-46 PostgreSQL 备库入口脚本
# ------------------------------------------------------------------------------
# 职责：把"建备库"这件手工活自动化成"容器首次启动的一次 pg_basebackup"。
#
# 为什么需要它（而不是在 compose 里 declarative 地配）：
#   PostgreSQL 的备库不是一个独立可初始化的节点 —— 它必须先从主库拉一份**基准备份**，
#   再以 recovery 模式启动。compose 没有表达"拉备份"的能力，只能靠入口脚本。
#
# 幂等性：以 PGDATA/PG_VERSION 是否存在判定"是否已初始化"，重复启动不会重复拉备份。
#
# 前置条件（由 scripts/ha-standby-init.ps1 保证）：
#   主库上存在复制角色 repl（LOGIN REPLICATION）且 pg_hba 允许本网段的 replication 连接。
#
# 为什么不直接 `pg_basebackup -R`：
#   -R 是否把密码写进 primary_conninfo 属于实现细节，且会与下面显式追加的
#   primary_conninfo 形成重复配置。这里改为显式写入（确定行为 > 依赖约定）。
# ==============================================================================
set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-postgres}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPL_USER="${REPL_USER:-repl}"
REPL_PASSWORD="${REPL_PASSWORD:-}"
STANDBY_NAME="${STANDBY_NAME:-standby-1}"

if [ -z "$REPL_PASSWORD" ]; then
    echo "[standby] 错误: 未设置 REPL_PASSWORD（复制角色密码），无法做主准备份" >&2
    exit 1
fi

if [ -s "$PGDATA/PG_VERSION" ]; then
    echo "[standby] 检测到已有数据目录（$PGDATA），直接以备库身份启动"
    exec docker-entrypoint.sh "$@"
fi

echo "[standby] 数据目录为空，开始从 ${PRIMARY_HOST}:${PRIMARY_PORT} 做基准备份 ..."

# 残留文件（无 PG_VERSION 但目录非空）会让 pg_basebackup 拒绝写入，先清干净
if [ -n "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
    echo "[standby] 目录非空但无 PG_VERSION（上次初始化残留），清空后重试"
    find "$PGDATA" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
fi

# 以 postgres 用户执行：本脚本以 root 进入容器，若直接用 root 写 PGDATA，
# 生成的目录/文件属 root，随后 postgres 进程会因权限不足拒绝启动。
gosu postgres env PGPASSWORD="$REPL_PASSWORD" pg_basebackup \
    -h "$PRIMARY_HOST" \
    -p "$PRIMARY_PORT" \
    -U "$REPL_USER" \
    -D "$PGDATA" \
    -X stream \
    -c fast \
    -P

# 备库标识：PG12+ 用 standby.signal 文件（不再有 recovery.conf）
touch "$PGDATA/standby.signal"

# 主库连接信息：主库地址/复制角色/密码。application_name 会出现在主库
# pg_stat_replication 里，便于确认备库是否真的接上了。
{
    echo ""
    echo "# ==== V3.1 F-46 备库配置（由 standby-entrypoint.sh 写入，勿手工改） ===="
    echo "primary_conninfo = 'host=${PRIMARY_HOST} port=${PRIMARY_PORT} user=${REPL_USER} password=${REPL_PASSWORD} application_name=${STANDBY_NAME}'"
} >> "$PGDATA/postgresql.auto.conf"

# 上面以 root 写入，需把属主交还 postgres
chown postgres:postgres "$PGDATA/standby.signal" "$PGDATA/postgresql.auto.conf"
chmod 0600 "$PGDATA/postgresql.auto.conf"

echo "[standby] 基准备份完成：已写入 standby.signal 与 primary_conninfo(app=${STANDBY_NAME})"
echo "[standby] 交给官方入口脚本启动 postgres（进入 recovery 模式持续回放 WAL）"

exec docker-entrypoint.sh "$@"
