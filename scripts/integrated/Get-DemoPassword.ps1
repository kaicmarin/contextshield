function Get-DemoPassword {
    param(
        [Parameter(Mandatory = $true)][string]$Email,
        [string]$Root
    )
    if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path }

    function Resolve-SeedCredentialsPath {
        param([string]$ProjectRoot)
        if ($env:SEED_CREDENTIALS_FILE) { return $env:SEED_CREDENTIALS_FILE }
        return Join-Path $ProjectRoot "services\core-api\src\test\resources\seed\test-credentials.json"
    }

    $path = Resolve-SeedCredentialsPath $Root
    if (-not (Test-Path $path)) {
        throw "Arquivo de credenciais não encontrado: $path"
    }
    $json = Get-Content $path -Raw | ConvertFrom-Json
    $key = $Email.Trim().ToLower()
    $pw = $json.passwords.$key
    if (-not $pw) { throw "Senha não encontrada no arquivo de seed para $key" }
    return $pw
}