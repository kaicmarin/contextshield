# Confere os endereços locais. Não chama a AWS.
$ErrorActionPreference = "Continue"
$targets = @(
  "http://localhost:8080/actuator/health",
  "http://localhost:8081/actuator/health",
  "http://localhost:8082/actuator/health",
  "http://localhost:8083/actuator/health",
  "http://localhost:8084/actuator/health"
)
foreach ($url in $targets) {
  try {
    $body = Invoke-RestMethod -Uri $url -TimeoutSec 3
    Write-Output "$url $($body.status)"
  } catch {
    Write-Output "$url indisponível"
  }
}
