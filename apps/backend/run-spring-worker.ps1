param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('bootRun', 'once', 'check', 'benchmark', 'importCalendar', 'importInstruments', 'refreshKospiTop10', 'backfillCandles', 'backfillKospiTop10', 'prepareDemoTop50', 'prepareMarketData')]
    [string]$Task
)

$springRoot = $PSScriptRoot
$jdkRoot = Get-ChildItem -LiteralPath (Join-Path $springRoot '.tooling\jdk21') -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $jdkRoot) { throw 'JDK 21을 찾을 수 없습니다. apps/backend/.tooling/jdk21에 JDK 21을 설치하거나 JAVA_HOME을 설정하세요.' }

$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $springRoot '.gradle-user-home'
Push-Location $springRoot
try {
    if ($Task -eq 'bootRun') { & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'once') { $env:WORKER_ONCE = 'true'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'benchmark') { & .\gradlew.bat :signal-worker:rankingBenchmark }
    elseif ($Task -eq 'importCalendar') { $env:MARKET_DATA_ACTION = 'import-calendar'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'importInstruments') { $env:MARKET_DATA_ACTION = 'import-instruments'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'refreshKospiTop10') { $env:MARKET_DATA_ACTION = 'refresh-kospi-top10'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'backfillCandles') { $env:MARKET_DATA_ACTION = 'backfill-candles'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'backfillKospiTop10') { $env:MARKET_DATA_ACTION = 'backfill-kospi-top10'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'prepareDemoTop50') { $env:MARKET_DATA_ACTION = 'prepare-demo-top50'; & .\gradlew.bat :signal-worker:bootRun }
    elseif ($Task -eq 'prepareMarketData') { $env:MARKET_DATA_ACTION = 'prepare'; & .\gradlew.bat :signal-worker:bootRun }
    else { & .\gradlew.bat :signal-worker:test :signal-worker:build --no-daemon }
    exit $LASTEXITCODE
} finally { Pop-Location }
