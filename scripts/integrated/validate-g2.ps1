# Valida política G2 (Cache-Control) no server.mjs de desenvolvimento.
$fePort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { "5173" }
$base = "http://127.0.0.1:$fePort"
$root = Resolve-Path "$PSScriptRoot\..\.."
$map = Test-Path "$root\frontend\nginx-cache-map.conf"
$nginx = (Get-Content "$root\frontend\nginx.conf" -Raw) -match '\$asset_cache_control'
function Head-Cache($url) {
    $h = curl.exe -sS -I $url 2>$null | Out-String
    if ($h -match '(?i)Cache-Control:\s*([^\r\n]+)') { return $Matches[1].Trim() }
    return ""
}
$ccUnv = Head-Cache "$base/assets/js/home.js"
$ccVer = Head-Cache "$base/assets/js/ops.js?v=31"
$ok = ($map -and $nginx) -and ($ccUnv -match 'no-cache') -and ($ccVer -match 'immutable')
Write-Host "G2 map=$map nginx=$nginx"
Write-Host "  sem ?v= ($ccUnv)"
Write-Host "  com ?v= ($ccVer)"
if ($ok) { Write-Host "G2: OK"; exit 0 }
Write-Host "G2: FALHA"; exit 1