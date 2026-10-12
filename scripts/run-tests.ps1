<#
.SYNOPSIS
    运行项目测试(本地/任意 CI 均可调用)。

.DESCRIPTION
    两档验证, 与 pom.xml 的插件分档一一对应:

      默认      mvn test      —— 单元 + 装配测试(约 15 秒)
                                零外部依赖: 不连数据库/网络, 不需要 API Key, 不产生费用
      -Full     mvn verify    —— 在上述基础上追加集成测试(*IT)
                                需要本机 Docker: RagVectorStoreIT 会以 Testcontainers
                                拉起真实的 pgvector 容器, 并用 docs/sql/*.sql 建表
                                (LangChain4jProviderIT 需要 DASHSCOPE_API_KEY, 未设置则自动跳过)

    脚本会临时清空 DASHSCOPE_API_KEY, 确保即使运行 -Full 也不会误触外部付费调用 ——
    需要真实联调模型时请手动设置该变量并单独执行 `mvn verify`。

    测试条数刻意不写死于注释: 它随测试演进持续增长, 写死会让文档很快过时
    (此前写"162 条", 实际已达 222 条)。实际条数以运行输出的 `Tests run: N` 为准。

.PARAMETER Full
    执行完整验证(含集成测试, 需要 Docker)。

.PARAMETER TestClass
    只运行指定测试类, 例如 -TestClass ApplicationContextTest。

.EXAMPLE
    .\scripts\run-tests.ps1
    跑全部单元测试。

.EXAMPLE
    .\scripts\run-tests.ps1 -Full
    跑单元测试 + 集成测试(起真实 pgvector 容器)。

.EXAMPLE
    .\scripts\run-tests.ps1 -TestClass RecursiveSplitterTest
    只跑一个测试类。
#>
[CmdletBinding()]
param(
    [switch]$Full,
    [string]$TestClass
)

# 注意: 此处**不能**设置 $ErrorActionPreference = 'Stop' ——
# PowerShell 会把**原生命令**(java / mvn)写到 stderr 的任何输出都当作错误并抛
# NativeCommandError, 哪怕命令实际执行成功(实测: JVM 的 "HotSpot warning..."、
# Maven 的告警都会触发)。故本脚本统一通过 $LASTEXITCODE 判断成败。

# 切到项目根(脚本所在目录的上一级)
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

# 项目要求 JDK 17。注意: 本机 JAVA_HOME 实测指向 JDK 8(D:\jdk1.8.0_202),
# 若直接继承会让 Maven 以 JDK 8 编译而失败, 故此处按候选列表挑出 >= 17 的那个。
function Get-JavaMajorVersion([string]$javaExe) {
    if (-not (Test-Path $javaExe)) { return 0 }
    # `java -version` 把版本信息写到 **stderr**; 在本脚本 ErrorActionPreference=Stop 的前提下,
    # PowerShell 会把原生命令的 stderr 视作错误并抛出 NativeCommandError, 故此处临时放宽。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $firstLine = [string](& $javaExe -version 2>&1 | Select-Object -First 1)
        if ($firstLine -match 'version "1\.(\d+)') { return [int]$Matches[1] }   # JDK 8 及更早: "1.8.0_202"
        if ($firstLine -match 'version "(\d+)') { return [int]$Matches[1] }      # JDK 9+ : "17.0.1"
        return 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$jdkCandidates = @('D:\app\jdk17', $env:JAVA_HOME) | Where-Object { $_ }
foreach ($jdk in $jdkCandidates) {
    if ((Get-JavaMajorVersion (Join-Path $jdk 'bin\java.exe')) -ge 17) {
        $env:JAVA_HOME = $jdk
        break
    }
}
if ((Get-JavaMajorVersion (Join-Path $env:JAVA_HOME 'bin\java.exe')) -lt 17) {
    throw "需要 JDK 17+, 但 JAVA_HOME=$env:JAVA_HOME; 请安装 JDK 17 或调整脚本中的候选路径"
}

$mavenBin = 'D:\Maven\apache-maven-3.9.0\bin'
if (Test-Path $mavenBin) { $env:PATH = "$env:JAVA_HOME\bin;$mavenBin;$env:PATH" }

# 防御: 避免误触外部付费调用(单元测试本就不需要 Key)
$env:DASHSCOPE_API_KEY = ''

$goal = if ($Full) { 'verify' } else { 'test' }
$args = @('-B', $goal)
if ($TestClass) { $args += "-Dtest=$TestClass" }

Write-Host "==> mvn $($args -join ' ')  (工作目录: $projectRoot)" -ForegroundColor Cyan
& mvn @args
$code = $LASTEXITCODE

if ($code -eq 0) {
    Write-Host "==> 通过" -ForegroundColor Green
} else {
    Write-Host "==> 失败 (exit=$code), 报告见 target/surefire-reports 或 target/failsafe-reports" -ForegroundColor Red
}
exit $code
