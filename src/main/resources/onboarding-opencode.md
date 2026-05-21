# Onboarding - Infinito Launcher

## ¿Qué hace esta aplicación?

**Infinito Launcher** es un lanzador gráfico (JavaFX) que gestiona servicios locales de desarrollo para el proyecto **Infinito**:

- **Base de Datos** (PostgreSQL via Docker, puerto 5432)
- **Servicio de Seguridad** (Spring Boot JAR, puerto 8081)
- **Servicio de Correos SMTP** (Spring Boot JAR, puerto 8082)
- **Lógica Tienda** (Spring Boot JAR, puerto 8088)
- **Frontend** (Angular via npm, puerto 4200)

Permite iniciar, detener, reiniciar y actualizar (git pull + build) cada servicio desde una interfaz gráfica, además de monitorear su estado vía health checks HTTP/TCP.

## Stack técnico

- **Java 11** con módulos (JPMS)
- **JavaFX 11** para interfaz gráfica
- **Maven** para build y dependencias
- **Spring Boot** (servicios backend)
- **Angular** (frontend)
- **Docker** (PostgreSQL)

## Cambios realizados (adaptación a Linux)

### 1. `pom.xml` — Dependencias JavaFX multi-plataforma

- **Problema**: Las dependencias JavaFX tenían `<classifier>win</classifier>` hardcodeado, solo funcionaban en Windows.
- **Solución**: Se reemplazó por `<classifier>${javafx.classifier}</classifier>` con perfiles Maven que detectan automáticamente el SO:
  - Perfil `linux` → classifier `linux`
  - Perfil `windows` → classifier `win`
  - Perfil `mac` → classifier `mac`
- Se agregó `javafx-maven-plugin` para facilitar la ejecución con `mvn javafx:run`.

### 2. `AppConfig.java` — Ruta base por defecto

- **Antes**: `C:/dev/repos`
- **Ahora**: `$HOME/dev/repos`
- Se adapta automáticamente al directorio home del usuario en Linux.

### 3. `ServiceManager.java` — Comandos de sistema

| Función | Windows (original) | Linux (nuevo) |
|---|---|---|
| `LOGS_DIRECTORY` | `C:/dev/repos/.../logs` | `$HOME/.infinitesoft/logs` |
| `start()` | `cmd.exe /c start /B ...` | `sh -c "... > log 2>&1"` |
| `killProcessOnPort()` | `netstat -ano \| findstr` + `taskkill` | `lsof -ti :port` + `kill -9` |
| `updateProject()` | `cmd.exe /c git pull / mvn / npm` | comandos directos `git pull`, `mvn`, `npm` |
| `ensureFirewallRuleExists()` | PowerShell con UAC | Eliminado (no aplica en Linux) |

### 4. `HelloController.java` — Rutas de aplicaciones externas

- **onLaunchApp()**: Chrome/Edge paths Windows → `google-chrome`, `chromium-browser`, `firefox`
- **onOpenLogs()**: `C:/dev/repos/.../logs` → `$HOME/.infinitesoft/logs`
- **onOpenDbManager()**: `C:\Program Files\DBeaver\dbeaver.exe` → `dbeaver` / `dbeaver-ce`

### 5. `HelloController.java` — Bug DirectoryChooser

- **Problema**: `setInitialDirectory()` lanzaba `IllegalArgumentException` si la carpeta no existía.
- **Solución**: Se valida que el directorio exista antes de asignarlo como inicial.

## Cómo ejecutar

```bash
mvn javafx:run
```

O desde IntelliJ: crear Run Configuration Maven con comando `javafx:run`.
