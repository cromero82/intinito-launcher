# Prepara esta máquina Windows para el POS Infinito.
# Uso: powershell -ExecutionPolicy Bypass -File prepare-host.ps1 <ambiente> [puerto ...]
# El túnel Cloudflare no requiere puertos públicos. Estas reglas son para la LAN.

param(
    [Parameter(Position = 0)][string]$EnvId = "dev-local",
    [Parameter(ValueFromRemainingArguments = $true)][int[]]$Ports
)

Write-Host "Sistema: Windows  Ambiente: $EnvId"
switch ($EnvId) {
    "sandbox" { Write-Host "Hostname público: https://pos-sandbox.mayaksoluciones.com" }
    "tienda-infinito" { Write-Host "Hostname público: https://tienda-infinito.mayaksoluciones.com  (Caddy :8280, BD controlneg_rmx_db_v02)" }
    default { Write-Host "Hostname público: https://cotiza.mayaksoluciones.com  y  http://localhost:4200" }
}

function Test-Cmd($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { Write-Host "OK  $name → $($cmd.Source)" }
    else { Write-Host "FALTA  $name  (instálalo antes de arrancar ese servicio desde el launcher)" }
}

Write-Host ""
Write-Host "Comandos en PATH:"
Test-Cmd java
Test-Cmd mvn
Test-Cmd docker
Test-Cmd npm
Test-Cmd caddy
Test-Cmd cloudflared

Write-Host ""
Write-Host "Puertos LAN a permitir: $($Ports -join ', ')"

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "Para crear reglas de firewall ejecuta PowerShell como Administrador:"
    Write-Host "  powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" $EnvId $($Ports -join ' ')"
    exit 0
}

foreach ($p in $Ports) {
    $ruleName = "Infinito POS $EnvId TCP $p"
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "Ya existía: $ruleName"
        continue
    }
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $p -Action Allow | Out-Null
    Write-Host "Creada: $ruleName"
}

if ($EnvId -eq "tienda-infinito") {
    $tokenFile = Join-Path $env:USERPROFILE ".cloudflared\tienda-infinito.token"
    if (Test-Path $tokenFile) { Write-Host "OK  token del túnel Tienda Infinito: $tokenFile" }
    else { Write-Host "FALTA  $tokenFile  (cópialo desde la laptop de desarrollo; sin él el túnel de producción no conecta)" }
}

Write-Host ""
Write-Host "El launcher instala cloudflared como inicio de sesión (tarea ONLOGON)."
Write-Host "Listo. Arranca los servicios desde Infinito Launcher (uno a uno o Iniciar todo)."
