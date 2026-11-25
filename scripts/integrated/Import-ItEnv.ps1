# Carrega it.env.example e, se existir, it.env (sobrescreve).
function Import-ItEnv {
    param([string]$Dir = $PSScriptRoot)
    $vars = [ordered]@{}
    foreach ($name in @("it.env.example", "it.env")) {
        $file = Join-Path $Dir $name
        if (-not (Test-Path $file)) { continue }
        Get-Content $file | ForEach-Object {
            $line = $_.Trim()
            if (-not $line -or $line.StartsWith("#")) { return }
            $eq = $line.IndexOf("=")
            if ($eq -lt 1) { return }
            $k = $line.Substring(0, $eq).Trim()
            $v = $line.Substring($eq + 1).Trim()
            $vars[$k] = $v
        }
    }
    foreach ($k in $vars.Keys) { Set-Item -Path "Env:$k" -Value $vars[$k] }
}

function Get-ItRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-PsqlPath {
    if ($env:PSQL_BIN) { return $env:PSQL_BIN }
    $candidates = @(
        "${env:ProgramFiles}\PostgreSQL\18\bin\psql.exe",
        "${env:ProgramFiles}\PostgreSQL\17\bin\psql.exe"
    )
    foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
    $cmd = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "psql nao encontrado; defina PSQL_BIN."
}