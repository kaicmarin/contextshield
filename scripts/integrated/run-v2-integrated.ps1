# Senhas individuais + motor dinâmico (IT local).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
& (Join-Path $PSScriptRoot "run-password-suite.ps1") -SkipE2E
& (Join-Path $PSScriptRoot "run-dynamic-rules-it.ps1")
& (Join-Path $PSScriptRoot "run-ml-queue-it.ps1")
& (Join-Path $PSScriptRoot "smoke-full.ps1")
Write-Host "Suite V2 integrada concluída."