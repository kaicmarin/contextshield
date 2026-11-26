# IT: três experimentos consecutivos (fila ml-service 18082).
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
Import-ItEnv
$base = "http://127.0.0.1:18080"
$anTok = (Invoke-ItLogin -Base $base -Email analista@contextshield.example -Password (Get-ItLoginPassword -Email analista@contextshield.example -Root (Get-ItRoot))).Json.token

function Poll($id) {
    for ($i = 0; $i -lt 180; $i++) {
        $f = [IO.Path]::GetTempFileName()
        & curl.exe -sS -o $f -w "`nHTTP:%{http_code}" -H "Authorization: Bearer $anTok" "$base/api/ops/ml/experiments/$id" | Out-Null
        $raw = [IO.File]::ReadAllText($f)
        Remove-Item $f
        $lines = $raw.TrimEnd() -split "`r?`n"
        $body = if ($lines[-1] -match '^HTTP:') { ($lines[0..($lines.Count-2)] -join "`n") } else { $raw }
        $j = $body | ConvertFrom-Json
        if ($j.status -in @("DONE", "FAILED")) { return $j }
        Start-Sleep 2
    }
    throw "Timeout experimento $id"
}

$ids = @()
foreach ($seed in @(31, 32, 33)) {
    $file = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($file, (@{ examples = 220; seed = $seed } | ConvertTo-Json -Compress))
    $created = & curl.exe -sS -X POST -H "Authorization: Bearer $anTok" -H "Content-Type: application/json" --data-binary "@$file" "$base/api/ops/ml/experiments"
    Remove-Item $file
    $ids += ($created | ConvertFrom-Json).id
}
$results = @()
foreach ($id in $ids) {
    $results += Poll $id
}
$bad = @($results | Where-Object { $_.status -ne "DONE" })
if ($bad.Count -gt 0) { throw "Experimentos falharam: $($bad | ConvertTo-Json -Compress)" }
Write-Host "OK — $($ids.Count) experimentos DONE: $($ids -join ', ')"
exit 0