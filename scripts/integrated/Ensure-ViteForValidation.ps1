# Sobe o Vite em 5173 para ui-it e E2E (somente se ainda nao responder).
param([string]$Root)
if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path }
$port = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { "5173" }
$script:ValidationViteBase = "http://127.0.0.1:$port/"
$script:ValidationViteStarted = $false
$script:ValidationVitePid = $null

function Test-ValidationVite {
    try {
        $r = Invoke-WebRequest -Uri $script:ValidationViteBase -UseBasicParsing -TimeoutSec 4
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Start-ValidationVite {
    if (Test-ValidationVite) { return $true }
    $fe = Join-Path $Root "frontend"
    if (-not (Test-Path (Join-Path $fe "package.json"))) { throw "frontend ausente em $fe" }
    $logDir = Join-Path $Root "logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $log = Join-Path $logDir "validation-vite.log"
    $npmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npmCmd) { $npmCmd = Get-Command npm -ErrorAction SilentlyContinue }
    if (-not $npmCmd) { throw "npm nao encontrado no PATH" }
    $args = @("run", "dev", "--", "--host", "127.0.0.1", "--port", $port)
    $proc = Start-Process -FilePath $npmCmd.Source -ArgumentList $args -WorkingDirectory $fe -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError $log
    $script:ValidationVitePid = $proc.Id
    $script:ValidationViteStarted = $true
    for ($i = 0; $i -lt 90; $i++) {
        if (Test-ValidationVite) { return $true }
        if ($proc.HasExited) {
            $tail = if (Test-Path $log) { Get-Content $log -Tail 8 -ErrorAction SilentlyContinue } else { @() }
            throw "Vite encerrou (pid=$($proc.Id)). Log: $($tail -join ' | ')"
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Stop-ValidationVite {
    if (-not $script:ValidationViteStarted -or -not $script:ValidationVitePid) { return }
    Stop-Process -Id $script:ValidationVitePid -Force -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "vite.*$port" -or $_.CommandLine -match "--port[\s=]$port" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-ItDemoReset {
    param([string]$ApiBase, [string]$ProjectRoot)
    . (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
    Import-ItEnv -ErrorAction SilentlyContinue
    $pw = Get-ItLoginPassword -Email "admin@contextshield.example" -Root $ProjectRoot -Base $ApiBase
    $login = Invoke-ItLogin -Base $ApiBase -Email "admin@contextshield.example" -Password $pw
    if (-not $login.Json.token) { return @{ Ok = $false; Detail = "login admin HTTP $($login.Code)" } }
    $tok = $login.Json.token
    $raw = curl.exe -sS -w "`nHTTP:%{http_code}" -X POST -H "Authorization: Bearer $tok" "$ApiBase/api/ops/demo/reset"
    $text = ($raw | Out-String).TrimEnd()
    $lines = $text -split "`r?`n"
    $code = "000"
    if ($lines[-1] -match '^HTTP:(\d+)$') { $code = $Matches[1]; $text = ($lines[0..($lines.Count - 2)] -join "`n") }
    $ok = ($code -eq "200") -and ($text -match '"status"\s*:\s*"ok"')
    if ($ok) {
        $anPw = Get-ItLoginPassword -Email "analista@contextshield.example" -Root $ProjectRoot -Base $ApiBase
        $anLogin = Invoke-ItLogin -Base $ApiBase -Email "analista@contextshield.example" -Password $anPw
        if ($anLogin.Json.token) {
            $anTok = $anLogin.Json.token
            $rules = curl.exe -sS -H "Authorization: Bearer $anTok" "$ApiBase/api/ops/rules"
            try {
                $parsed = $rules | ConvertFrom-Json
                foreach ($r in @($parsed | Where-Object { $_.ruleKey -eq "R13" -and $_.status -eq "PUBLISHED" })) {
                    curl.exe -sS -X POST -H "Authorization: Bearer $anTok" "$ApiBase/api/ops/rules/$($r.id)/disable" | Out-Null
                }
            } catch { }
        }
    }
    return @{ Ok = $ok; Detail = "HTTP $code $text" }
}