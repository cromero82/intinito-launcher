# Onboarding - Infinito Launcher

**Infinito Launcher** (`intinito-launcher`, el nombre del repo lleva un typo histórico) es un panel JavaFX para **arrancar y parar** los procesos del POS en **esta máquina**. No sustituye a IntelliJ: es el control de lo que ya está compilado (JAR / npm).

**Contexto general de la app e instalación en producción:** el repo hermano `prompts-general-pos/` (mismo path `…/repos/`). En la PC de tienda abre `CURSOR-IA-PC-TIENDA-V02.md`. Este launcher no guarda el runbook ni aplica migraciones.

Sirve en **Windows, macOS y Linux**. Elige el ambiente arriba y luego inicia servicios uno a uno, o **Iniciar todo**.

## Ambientes

| Ambiente | Para qué | Front | POS | Puente | Caddy | Hostname público |
|---|---|---|---|---|---|---|
| **Dev local** | Laptop de desarrollo | 4200 | 8088 | 8095 | 8080 | `cotiza.mayaksoluciones.com` |
| **Sandbox** | Copia del tester (`repos/sandbox/…`) | 4210 | 8188 | 8195 | 8180 | `pos-sandbox.mayaksoluciones.com` |
| **Caja actual** | Copia productiva vieja (`controlneg_rmx_db`) | 4200 | 8088 | — | — | — (sin notificaciones) |
| **Tienda Infinito** | Producción (`controlneg_rmx_db_v02` ya migrada) + correo CF | 4220 | 8288 | 8295 | 8280 | `tienda-infinito.mayaksoluciones.com` |

**Tienda Infinito** no tiene **Ejecutar scripts**: la BD llega lista. Solo **Iniciar todo** / Preparar esta máquina.

La ruta de proyectos (pie de ventana) es la carpeta `repos`. En Sandbox el launcher entra solo a `repos/sandbox`. En Dev y Tienda Infinito usa los proyectos de esa carpeta.

## Servicios (uno a uno o todos)

Ya estaban: Base de datos, Seguridad, SMTP, Lógica tienda, Frontend.

Nuevos, porque el correo de banco no llega sin ellos:

- **Puente** — recibe el POST del Worker de Cloudflare (`/api/email-inbound`).
- **Caddy** — portero local. El túnel deja el HTTPS en un puerto; Caddy lo pasa al puente / Angular / auth.
- **Túnel Cloudflare** — cable a internet. El launcher lo deja como **inicio de sesión** para que sobreviva el reinicio:
  - macOS: LaunchAgent (`~/Library/LaunchAgents/com.infinitesoft.cloudflared.*.plist`)
  - Linux: systemd --user + `.desktop` en `~/.config/autostart`
  - Windows: tarea programada ONLOGON (`InfinitoCloudflared-*`) con bucle si `cloudflared` se cae  
  Iniciar / Reiniciar reaniman ese servicio en los tres SO. Detener todo no lo mata. Dev/Sandbox: `pos-local`. Tienda Infinito: token propio.

**Iniciar todo** levanta en orden los Java, el front, el puente, Caddy y asegura el túnel. No toca Postgres Docker. Detener todo no apaga el túnel.

## Preparar esta máquina (puertos / firewall)

El botón **Preparar esta máquina** corre un script según el sistema:

- macOS / Linux: `prepare-host.sh`
- Windows: `prepare-host.ps1` (mejor como Administrador si vas a crear reglas de firewall)

El túnel de Cloudflare **no necesita puertos abiertos a internet** (sale él hacia Cloudflare). Las reglas son para que una tablet o otra caja en la **LAN** llegue a esta PC (en v02: 4220, 8288, 8280, etc.).

En Linux puede pedir `sudo`. En Windows, PowerShell como Administrador.

## Cómo ejecutar el launcher

```bash
cd intinito-launcher
mvn -DskipTests package
java -jar target/infinito-launcher.jar
```

macOS: `./run-macos.sh` · Linux: `./infinito-launcher.sh` · Windows: el mismo `java -jar` con un JDK 11+.

JavaFX se elige con perfiles Maven (`mac` / `linux` / `windows`); no hace falta un jar distinto por lógica de negocio.

## Tienda Infinito (cuando montes esa PC)

Instalación / PC de tienda: **`prompts-general-pos/CURSOR-IA-PC-TIENDA-V02.md`** (la BD llega ya migrada; este launcher no aplica scripts). Índice de docs: `prompts-general-pos/README.md`.
