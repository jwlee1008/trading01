$workspaceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$apiJob = Start-Job -ArgumentList $workspaceRoot -ScriptBlock {
    param($root)
    Set-Location -LiteralPath $root
    powershell -ExecutionPolicy Bypass -File apps\backend\run-spring-api.ps1 bootRun
}
$workerJob = Start-Job -ArgumentList $workspaceRoot -ScriptBlock {
    param($root)
    Set-Location -LiteralPath $root
    powershell -ExecutionPolicy Bypass -File apps\backend\run-spring-worker.ps1 bootRun
}

try {
    Set-Location -LiteralPath $workspaceRoot
    pnpm dev:mobile
} finally {
    Stop-Job -Job $apiJob, $workerJob -ErrorAction SilentlyContinue
    Receive-Job -Job $apiJob, $workerJob -ErrorAction SilentlyContinue
    Remove-Job -Job $apiJob, $workerJob -Force -ErrorAction SilentlyContinue
}
