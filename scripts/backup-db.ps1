<#
  V3.1 防复发 (2026-09-28): Windows 侧 PostgreSQL 定时备份脚本

  背景: 2026-09-28 Docker Desktop 被重置到出厂默认, 数据盘 vhdx(E:\appdata\docker\DockerDesktop)
  连同其中的命名卷 pgdata 一起被删除, 全部业务数据丢失; 而当时容器内的备份目录
  (/var/backups/dcdr-agent) 同样活在 vhdx 里, 一起没了 —— 等于没有备份。

  本脚本的两条硬约束(违反任一条都会重演事故):
    1. 备份必须写到**宿主机可见**的目录: compose 中 `./data/backup:/backup`,
       即宿主机 <项目>/data/backup —— 与 Docker VM 的 vhdx 解耦。
    2. 不使用 PowerShell 的 `>` 重定向接 pg_dump 输出: PS 5.1 会按文本解码,
       二进制 dump 必然损坏。改为在容器内 `pg_dump -f /backup/...` 直写 bind mount 目录。

  用法:
    powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1
    powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1 -RetainDays 30

  计划任务(每日 02:00, 需管理员):
    $act = New-ScheduledTaskAction -Execute 'powershell.exe' `
             -Argument '-ExecutionPolicy Bypass -File "D:\Projects\java-agent\scripts\backup-db.ps1"'
    $trg = New-ScheduledTaskTrigger -Daily -At 02:00
    Register-ScheduledTask -TaskName 'dcdr-agent-db-backup' -Action $act -Trigger $trg -RunLevel Highest
#>
[CmdletBinding()]
param(
    [string]$Container = 'dcdr-agent-pg',
    [string]$DbName    = 'dcdr_agent',
    [string]$DbUser    = 'dcdr_agent',
    # 注意: 不能在 param() 默认值里用 $PSScriptRoot —— PS 5.1 求值参数默认值时该变量尚未赋值,
    #       会得到空串导致 Split-Path 报错。改为脚本体内解析。
    [string]$BackupDir = '',
    [int]$RetainDays   = 14
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($BackupDir)) {
    $BackupDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'data\backup'
}

$dbDir = Join-Path $BackupDir 'db'
New-Item -ItemType Directory -Force -Path $dbDir | Out-Null
$logFile = Join-Path $BackupDir 'backup.log'

function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

# 容器是否在跑(没跑就不要产生"看起来成功"的假象)
$running = (& docker inspect --format '{{.State.Running}}' $Container 2>$null)
if ($running -ne 'true') {
    Write-Log "跳过: 容器 $Container 未运行 (docker compose up -d postgres 后重试)"
    exit 1
}

$stamp   = Get-Date -Format 'yyyyMMdd_HHmmss'
$inPath  = "/backup/db/${DbName}_${stamp}.dump"   # 容器内路径, 经 bind mount 落宿主机 $dbDir
$hostPath = Join-Path $dbDir "${DbName}_${stamp}.dump"

try {
    # -Fc: 自定义压缩格式, 支持并行恢复与选择性恢复(与 scripts/backup-db.sh 同口径)
    & docker exec $Container pg_dump -U $DbUser -d $DbName -Fc -f $inPath
    if ($LASTEXITCODE -ne 0) { throw "pg_dump 退出码 $LASTEXITCODE" }

    # 校验备份可读(pg_restore --list, 不实际恢复)
    & docker exec $Container pg_restore --list $inPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "备份校验失败 (pg_restore --list)" }

    if (-not (Test-Path $hostPath)) { throw "宿主机未看到备份文件: $hostPath (bind mount 配置异常?)" }
    $mb = [math]::Round((Get-Item $hostPath).Length / 1MB, 2)
    Write-Log "备份完成 -> $hostPath (${mb} MB), 校验通过"
} catch {
    Write-Log "备份失败: $($_.Exception.Message)"
    exit 1
}

# 保留策略: 清理超过 RetainDays 的 *.dump
$expired = Get-ChildItem -Path $dbDir -Filter '*.dump' |
           Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$RetainDays) }
if ($expired) {
    $expired | Remove-Item -Force
    Write-Log "已清理过期备份 $($expired.Count) 个 (>${RetainDays} 天)"
}
Write-Log "结束 (保留策略: ${RetainDays} 天, 目录: $dbDir)"
