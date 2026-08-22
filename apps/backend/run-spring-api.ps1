param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('bootRun', 'check')]
    [string]$Task
)

$springRoot = $PSScriptRoot
$jdkRoot = Get-ChildItem -LiteralPath (Join-Path $springRoot '.tooling\jdk21') -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $jdkRoot) {
    throw 'JDK 21을 찾을 수 없습니다. apps/backend/.tooling/jdk21에 JDK 21을 설치하거나 JAVA_HOME을 설정하세요.'
}

$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $springRoot '.gradle-user-home'

Push-Location $springRoot
try {
    if ($Task -eq 'bootRun') {
        & .\gradlew.bat :signal-api:bootRun
    } else {
        & .\gradlew.bat :signal-api:test :signal-api:build --no-daemon
    }
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
