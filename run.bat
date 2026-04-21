@echo off
cd /d "%~dp0"
java -jar infinito-launcher.jar
if errorlevel 1 (
    echo.
    echo Error al iniciar la aplicacion. Revisa que tengas Java 11 instalado.
    echo Ejecuta: java -version
    pause
)
