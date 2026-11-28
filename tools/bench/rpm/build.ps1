param(
    [string]$RpmRoot = ".tmp/rpm/resourcepackmanager-mixer",
    [string]$GsonJar = ".tmp/rpm-bench/lib/gson-2.11.0.jar",
    [string]$OutDir = ".tmp/rpm-bench/classes",
    [string]$Proxy = "http://127.0.0.1:7890"
)
$ErrorActionPreference = "Stop"
$engine = Join-Path $RpmRoot "src/main/java/com/magmaguy/resourcepackmanager/mixer/engine"
if (-not (Test-Path $engine)) { throw "找不到 RPM 源码：$engine（先 git clone ResourcePackManager 到 .tmp/rpm）" }
if (-not (Test-Path $GsonJar)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $GsonJar) | Out-Null
    $curlArgs = @("-sS")
    if ($Proxy) { $curlArgs += @("-x", $Proxy) }
    curl.exe @curlArgs -o $GsonJar "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
    if ($LASTEXITCODE -ne 0) { throw "下载 gson 失败：$LASTEXITCODE（可用 -Proxy 指定或手动放到 $GsonJar）" }
}
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:/Program Files/Zulu/zulu-25" }
$javac = Join-Path $javaHome "bin/javac.exe"
if (-not (Test-Path $javac)) { throw "找不到 javac：$javac（设置 JAVA_HOME 或装 JDK 17+）" }
Remove-Item -Recurse -Force $OutDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java $engine | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 17 -cp $GsonJar -d $OutDir $sources (Join-Path $PSScriptRoot "RpmHarness.java")
if ($LASTEXITCODE -ne 0) { throw "javac 失败：$LASTEXITCODE" }
Write-Host "classes -> $OutDir"
