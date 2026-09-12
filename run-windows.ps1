# Infinito Launcher — Windows
# Requiere JDK 11+ en PATH. Compila si aún no existe el JAR.

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java no está en PATH. Instala JDK 11+ (Adoptium) y vuelve a intentar."
}

$jar = Join-Path $PSScriptRoot "target\infinito-launcher.jar"
if (-not (Test-Path $jar)) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Error "No hay JAR y Maven no está en PATH. Instala Maven o copia infinito-launcher.jar a target\."
    }
    Write-Host "Compilando launcher..."
    mvn -DskipTests package
}

Write-Host "Arrancando Infinito Launcher..."
java -jar $jar
