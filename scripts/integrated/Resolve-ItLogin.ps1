. (Join-Path $PSScriptRoot "Get-DemoPassword.ps1")

function Invoke-ItLogin {
    param([string]$Base, [string]$Email, [string]$Password)
    $body = (@{ email = $Email; password = $Password } | ConvertTo-Json -Compress)
    $file = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($file, $body, [System.Text.UTF8Encoding]::new($false))
    $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", "POST", "-H", "Content-Type: application/json", "--data-binary", "@$file", "$Base/api/auth/login")
    $raw = & curl.exe @curl
    Remove-Item $file -Force
    $text = ($raw | Out-String).TrimEnd()
    $lines = $text -split "`r?`n"
    $code = "000"
    if ($lines.Count -ge 1 -and $lines[-1] -match '^HTTP:(\d+)$') {
        $code = $Matches[1]
        $text = if ($lines.Count -gt 1) { ($lines[0..($lines.Count - 2)] -join "`n") } else { "" }
    }
    return @{ Code = $code; Body = $text; Json = $(if ($code -eq "200") { $text | ConvertFrom-Json } else { $null }) }
}

function Get-ItLoginPassword {
    param(
        [Parameter(Mandatory = $true)][string]$Email,
        [string]$Root,
        [string]$Base = "http://127.0.0.1:18080"
    )
    if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path }
    $candidates = [System.Collections.Generic.List[string]]::new()
    try { $candidates.Add((Get-DemoPassword -Email $Email -Root $Root)) } catch { }
    if ($env:DEMO_LOGIN_PASSWORD) { $candidates.Add($env:DEMO_LOGIN_PASSWORD) }
    $candidates.Add("contexto")
    $seen = @{}
    foreach ($pw in $candidates) {
        if (-not $pw -or $seen[$pw]) { continue }
        $seen[$pw] = $true
        $probe = Invoke-ItLogin -Base $Base -Email $Email -Password $pw
        if ($probe.Code -eq "200") { return $pw }
        if ($probe.Code -eq "429") { Start-Sleep -Seconds 65; $probe = Invoke-ItLogin -Base $Base -Email $Email -Password $pw; if ($probe.Code -eq "200") { return $pw } }
    }
    throw "Não foi possível autenticar $Email no IT (senhas candidatas esgotadas)."
}