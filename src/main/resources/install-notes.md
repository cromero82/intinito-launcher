# Instrucciones de instalación

Infinito Launcher es un JAR JavaFX. Compílalo **en el mismo sistema** donde lo vas a correr (macOS, Linux o Windows) para que Maven elija el native de JavaFX correcto.

## Requisitos

- JDK 11 o superior en PATH ([Adoptium](https://adoptium.net/temurin/releases/?version=11))
- Maven, solo si vas a compilar en esa máquina
- Para los servicios: Docker (Postgres), npm (front), Caddy, cloudflared, según lo que arranques

## Compilar y ejecutar

```bash
cd intinito-launcher
mvn -DskipTests package
java -jar target/infinito-launcher.jar
```

Atajos:

- macOS: `./run-macos.sh`
- Linux: `./run-linux.sh`
- Windows: `powershell -ExecutionPolicy Bypass -File .\run-windows.ps1`

El JAR generado se llama `target/infinito-launcher.jar`.

## Primera vez en una PC de tienda

En el launcher: ambiente **Tienda Infinito** → **Preparar esta máquina** (abre puertos LAN / revisa PATH / instala el túnel al inicio de sesión). Copia `~/.cloudflared/tienda-infinito.token` a esa cuenta de usuario.
