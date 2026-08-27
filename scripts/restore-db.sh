#!/usr/bin/env bash
# ============================================================================
# V3.0-rc F-40.11 PostgreSQL 恢复脚本 (恢复演练/灾难恢复用)
# 用法: ./restore-db.sh <备份文件.dump> [目标数据库名(默认 agent)]
#
# 演练流程 (每季度一次, 规划文档 §14.11):
#   1. 在测试环境用本脚本从最近备份恢复
#   2. 启动应用指向恢复库 (SPRING_PROFILES_ACTIVE=pg + 修改数据源)
#   3. 抽查: 登录/会话/知识库检索/工作流执行, 验证数据完整性
#   4. 记录 RTO 实测值, 与目标 (<= 30 分钟) 对比
# ============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-agent}"

DUMP_FILE="${1:?用法: restore-db.sh <备份文件.dump> [目标库名]}"
TARGET_DB="${2:-agent}"

if [ ! -f "${DUMP_FILE}" ]; then
    echo "错误: 备份文件不存在: ${DUMP_FILE}" >&2
    exit 1
fi

echo "[$(date '+%F %T')] 开始恢复 ${DUMP_FILE} -> ${TARGET_DB}"

# 1. 重建空库 (如已存在则中止, 避免误覆盖生产库; 确要覆盖请手动 DROP)
psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d postgres -tc \
     "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" | grep -q 1 && {
    echo "错误: 目标库 ${TARGET_DB} 已存在, 为防误覆盖请先手动删除或换名" >&2
    exit 1
}
psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d postgres \
     -c "CREATE DATABASE ${TARGET_DB}"

# 2. 恢复数据 (自定义格式, 单事务保证一致性)
pg_restore -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${TARGET_DB}" \
           --exit-on-error --single-transaction "${DUMP_FILE}"

# 3. 校验核心表行数 (表不存在或为空会显式提示, 便于演练 checklist)
for t in users sessions knowledge_bases documents workflows; do
    COUNT=$(psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${TARGET_DB}" -tc \
            "SELECT count(*) FROM ${t}" 2>/dev/null | tr -d ' ' || echo "N/A")
    echo "  表 ${t}: ${COUNT} 行"
done

echo "[$(date '+%F %T')] 恢复完成。请将应用数据源指向 ${TARGET_DB} 并抽查业务功能。"
