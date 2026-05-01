# AI Context - intinito-launcher (app lanzadora)

> Antes de tocar este repo: **lee tambien**
> `C:\dev\repos\logs-infinito\docs\AI-ONBOARDING.md`. Aqui solo va lo
> especifico de la app lanzadora. El contexto global del sistema POS,
> de los demas microservicios y de la migracion de monitoreo esta alli.

## Que es

App de escritorio JavaFX 11. Es el "panel de control" de toda la
plataforma POS: arranca/para/reinicia/actualiza cada servicio, verifica
su estado, abre el frontend y el dashboard de monitoreo, etc.

Stack: JavaFX 11.0.2, Java 11, Maven con shade plugin.

## Cambio reciente que hay que conocer (logs-infinito)

El launcher ahora gestiona tambien el stack de monitoreo (InfluxDB +
Grafana). Cada uno aparece como una **fila independiente** en el panel
de servicios, con sus propios botones Iniciar/Detener/Reiniciar. La
fila de Grafana ademas tiene "Abrir Dashboard" y "Diagnostico".

Iniciar Todo arranca **primero el monitoreo** y luego los demas (para
no perder los logs de arranque del ms negocio). Si el monitoreo falla,
no aborta - es best-effort.

## Componentes nuevos que hay que conocer

`com.infinitesoft.launcher.core.MonitoreoManager`
- Clase autonoma. NO hereda nada de `ServiceManager`.
- Invoca scripts PowerShell publicados en
  `C:/dev/repos/logs-infinito/scripts/*.ps1` (el path se construye con
  `AppConfig.getInstance().getBasePath()`).
- Polling HTTP nativo cada 2s a `/health` (Influx) y `/api/health`
  (Grafana) - NO usa scripts para esto, usa `HealthChecker`.
- Metodos sync (`startInflux`, etc.) + async (`*Async(callback)`).
- Devuelve `ScriptResult { exitCode, jsonSummary, fullOutput }`.
- `openGrafanaDashboard()` abre Chrome/Edge en modo `--app`.
- `shutdown()` apaga el polling. NO mata los procesos influxdb3/grafana
  (decision: que sigan vivos cuando el launcher se cierra). Si quieres
  cambiar este comportamiento, anade `monitoreo.stopAll()` en
  `ServiceManager.shutdown()`.

## Componentes modificados

- `core/ServiceManager` - instancia `MonitoreoManager`. En
  `startAllSequential` arranca el monitoreo PRIMERO. En `stopAll`
  invoca `monitoreo.stopAll()`. Expone `getMonitoreo()`.
- `HelloController` - 2 labels nuevos (`statusInflux`, `statusGrafana`).
  En `refreshUI` lee status del `MonitoreoManager`. 8 handlers nuevos
  (`onStart/Stop/Restart Influx/Grafana`, `onOpenGrafanaDashboard`,
  `onDiagnoseMonitoreo`).
- `resources/com/infinitesoft/launcher/hello-view.fxml` - separator +
  2 filas nuevas al final del scroll de servicios.

## Decision importante: Monitoreo NO es bloqueante

El boton "Ejecutar aplicacion" se habilita cuando DB + Security + Store
+ Front estan RUNNING. El monitoreo NO cuenta. El POS funciona aunque
el monitoreo este caido (los logs van al spool del ms negocio y se
reenvian cuando Influx se recupera).

## Contrato launcher <-> scripts PowerShell

Todos los scripts:
- Idempotentes (start* no hace nada si ya esta UP).
- Exit code claro: 0 ok, 1+ fail (cada uno con su tabla).
- Imprimen una linea JSON al final (parseable, opcional).
- Se invocan con
  `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <path>`.

Tabla completa: `C:/dev/repos/logs-infinito/docs/contrato-launcher.md`.

## Patron para anadir un nuevo boton de monitoreo

1. Si necesitas un nuevo script: crearlo en
   `logs-infinito/scripts/` siguiendo la convencion (idempotente, JSON
   al final, exit code claro).
2. Anadir un metodo en `MonitoreoManager` que lo invoque (sync + async).
3. Anadir un `@FXML` handler en `HelloController`.
4. Anadir el `<Button>` en `hello-view.fxml`.
5. Actualizar `docs/contrato-launcher.md` y `scripts/README.md`.

## Pendientes

- **Auto-instalacion** de Grafana e InfluxDB cuando las carpetas no
  existan (descarga desde GitHub releases). Hoy se asumen
  pre-descomprimidas en `logs-infinito/`.
- **Boton "Actualizar"** para Influx/Grafana - ahora cada servicio del
  POS tiene boton "Actualizar" que hace `git pull && mvn package`.
  Para las dos apps de monitoreo seria "descargar nueva release y
  reemplazar". No implementado.
