#!/usr/bin/env bash
# ============================================================================
# V3.0-rc F-40.11 PostgreSQL 定时备份脚本
# 用法: ./backup-db.sh (建议 crontab 每日 02:00 执行)
#   crontab -e
#   0 2 * * * /opt/dcdr-agent/scripts/backup-db.sh >> /var/log/dcdr-backup.log 2>&1
#
# 备份策略 (规划文档 §14.11):
#   - 数据库: pg_dump 自定义格式 (压缩), 每日全量, 保留 14 天
#   - 知识库文件 (./data/kb): tar 归档, 每日增量, 保留 30 天
#   - 配置: 建议纳入 Git 版本控制 (不含密钥), 不在本脚本范围
# ============================================================================
set -euo pipefail

# ---------- 配置 (按环境修改) ----------
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-agent}"
DB_USER="${DB_USER:-agent}"
# ⚠️ V3.1 防复发 (2026-09-28): BACKUP_DIR 必须是**宿主机可见**的目录(bind mount 挂进来的),
#    不能留在容器文件系统内 —— 容器内路径与 Docker VM 的 vhdx 同生共死: vhdx 被删时
#    数据与备份会一起消失(2026-09-28 已实际发生, 备份目录 /var/backups 随之一并丢失)。
#    当前约定: compose 中 `./data/backup:/backup`, 即宿主机 <项目>/data/backup。
BACKUP_DIR="${BACKUP_DIR:-/backup}"
DATA_DIR="${DATA_DIR:-/app/data}"       # 应用数据目录 (含 kb/ uploads/, 经 bind mount 落宿主机)
DB_RETAIN_DAYS=14                        # 数据库备份保留天数
FILE_RETAIN_DAYS=30                      # 文件备份保留天数
# --------------------------------------

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p "${BACKUP_DIR}/db" "${BACKUP_DIR}/files"

echo "[$(date '+%F %T')] 开始备份 ..."

# 1. 数据库全量备份 (pg_dump 自定义格式, 支持并行恢复与选择性恢复)
DB_FILE="${BACKUP_DIR}/db/${DB_NAME}_${TIMESTAMP}.dump"
pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
        -Fc -f "${DB_FILE}"
echo "  数据库 -> ${DB_FILE} ($(du -h "${DB_FILE}" | cut -f1))"

# 2. 校验备份文件可读 (pg_restore 列出内容, 不实际恢复)
pg_restore --list "${DB_FILE}" > /dev/null
echo "  备份校验通过 (pg_restore --list OK)"

# 3. 知识库/上传文件归档 (增量可改 rsync 到对象存储)
KB_FILE="${BACKUP_DIR}/files/kb_${TIMESTAMP}.tar.gz"
if [ -d "${DATA_DIR}/kb" ]; then
    tar -czf "${KB_FILE}" -C "${DATA_DIR}" kb uploads 2>/dev/null || \
        tar -czf "${KB_FILE}" -C "${DATA_DIR}" kb
    echo "  知识库文件 -> ${KB_FILE} ($(du -h "${KB_FILE}" | cut -f1))"
fi

# 4. 按保留策略清理过期备份
find "${BACKUP_DIR}/db"    -name "*.dump"    -mtime +${DB_RETAIN_DAYS}   -delete
find "${BACKUP_DIR}/files" -name "*.tar.gz"  -mtime +${FILE_RETAIN_DAYS} -delete

echo "[$(date '+%F %T')] 备份完成, 过期备份已清理 (db=${DB_RETAIN_DAYS}d, files=${FILE_RETAIN_DAYS}d)"
