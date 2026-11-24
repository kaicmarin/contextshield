# Para os contêineres deste compose. Não apaga o volume do banco.
$ErrorActionPreference = "Stop"
Set-Location (Resolve-Path (Join-Path $PSScriptRoot "..\.."))
docker compose -f docker-compose.aws-free.yml stop
