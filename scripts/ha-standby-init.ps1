# ==============================================================================
# dcdr-agent V3.1 F-46 备库初始化前置：在主库上创建复制角色 + 放行复制连接
# ------------------------------------------------------------------------------
# 为什么需要这一步：备库靠 pg_basebackup 从主库拉全量，这需要
#   ① 一个带 REPLICATION 权限的角色（不能用业务账号）
#   ② pg_hba.conf 允许来自 compose 网段的 replication 连接
# 这两件事都只发生在**主库**上，且无法在 compose 里声明，故用一个脚本兜住。
#
# 幂等：重复执行安全（角色存在则跳过；pg_hba 已有规则则不重复追加）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\ha-standby-init.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\ha-standby-init.ps1 -ReplPassword 'xxx'
#
# 执行后启动备库：
#   docker compose -f docker-compose.yml -f docker-compose.ha.yml --profile ha up -d pg-standby
# ==============================================================================

param(
    [string]$PrimaryContainer = 'dcdr-agent-pg',
    [string]$DbUser           = 'dcdr_agent',
    [string]$DbName           = 'dcdr_agent',
    [string]$ReplUser         = 'repl',
    [string]$ReplPassword     = 'repl_secret'
)

# 不设 $ErrorActionPreference='Stop'：调用原生命令(docker)时，它写到 stderr 的任何输出
# 都会让 PowerShell 抛 NativeCommandError（哪怕命令实际成功）。统一用 $LASTEXITCODE 判断。
$ErrorActionPreference = 'Continue'

function Invoke-Psql {
    param([string]$Sql, [switch]$TuplesOnly)
    $args = @('exec', $PrimaryContainer, 'psql', '-U', $DbUser, '-d', $DbName)
    if ($TuplesOnly) { $args += @('-tAc') } else { $args += @('-c') }
    $args += $Sql
    $out = & docker @args 2>&1
    return @{ ExitCode = $LASTEXITCODE; Output = ($out | Out-String).Trim() }
}

Write-Host "==> 检查主库容器 $PrimaryContainer 是否在运行"
$running = (& docker inspect -f '{{.State.Running}}' $PrimaryContainer 2>$null | Out-String).Trim()
if ($running -ne 'true') {
    Write-Host "主库容器未运行，请先执行: docker compose up -d postgres redis" -ForegroundColor Red
    exit 1
}

Write-Host "==> 检查复制角色 $ReplUser"
$exists = Invoke-Psql -Sql "SELECT 1 FROM pg_roles WHERE rolname='$ReplUser'" -TuplesOnly
if ($exists.ExitCode -ne 0) {
    Write-Host "查询角色失败: $($exists.Output)" -ForegroundColor Red
    exit 1
}
if ($exists.Output -eq '1') {
    Write-Host "    角色已存在，更新密码以确保与备库配置一致"
    $r = Invoke-Psql -Sql "ALTER ROLE $ReplUser WITH LOGIN REPLICATION PASSWORD '$ReplPassword'"
} else {
    Write-Host "    角色不存在，创建"
    $r = Invoke-Psql -Sql "CREATE ROLE $ReplUser WITH LOGIN REPLICATION PASSWORD '$ReplPassword'"
}
if ($r.ExitCode -ne 0) {
    Write-Host "创建/更新角色失败: $($r.Output)" -ForegroundColor Red
    exit 1
}
Write-Host "    复制角色就绪: $ReplUser" -ForegroundColor Green

Write-Host "==> 放行复制连接 (pg_hba.conf)"
# 容器内用 shell 判断，避免把 pg_hba.conf 拉回宿主再写回（宿主机改容器文件容易踩编码/换行坑）
$hbaCmd = "grep -qE '^host\s+replication\s+all\s+all' `$PGDATA/pg_hba.conf || echo 'host replication all all scram-sha-256' >> `$PGDATA/pg_hba.conf"
$hba = & docker exec $PrimaryContainer sh -c $hbaCmd 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "修改 pg_hba.conf 失败: $($hba | Out-String)" -ForegroundColor Red
    exit 1
}
$null = Invoke-Psql -Sql "SELECT pg_reload_conf()" -TuplesOnly
Write-Host "    pg_hba.conf 已放行并 reload" -ForegroundColor Green

Write-Host "==> 核对主库复制参数"
$wal = Invoke-Psql -Sql "SHOW wal_level" -TuplesOnly
$send = Invoke-Psql -Sql "SHOW max_wal_senders" -TuplesOnly
Write-Host "    wal_level=$($wal.Output)  max_wal_senders=$($send.Output)"
if ($wal.Output -ne 'replica' -and $wal.Output -ne 'logical') {
    Write-Host "    ⚠️ wal_level 不是 replica，流复制无法工作；需在主库 postgresql.conf 调整后重启" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "前置条件已就绪。下一步启动备库（首次启动会自动 pg_basebackup，约 10~60 秒）：" -ForegroundColor Cyan
Write-Host "  docker compose -f docker-compose.yml -f docker-compose.ha.yml --profile ha up -d pg-standby"
Write-Host "查看备份进度：" -ForegroundColor Cyan
Write-Host "  docker logs -f dcdr-agent-pg-standby"
