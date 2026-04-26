package com.infinitesoft.launcher.core;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Gestiona el stack de monitoreo (InfluxDB 3 Core + Grafana) de logs-infinito.
 *
 * <p>NO arranca los procesos directamente: invoca los scripts PowerShell
 * publicados en {@code logs-infinito/scripts/} (contrato documentado en
 * {@code logs-infinito/docs/contrato-launcher.md}). Cada script es
 * idempotente, devuelve exit code claro y una linea JSON al final.
 *
 * <p>El status RUNNING/STARTING/NOT_RUNNING/FAILED se calcula con polling
 * HTTP nativo (no via script) cada 2s, igual patron que {@link ServiceManager}.
 */
public class MonitoreoManager {

    public static final String NAME_INFLUX  = "monitoreo-influx";
    public static final String NAME_GRAFANA = "monitoreo-grafana";

    private static final String INFLUX_HEALTH  = "http://127.0.0.1:8181/health";
    private static final String GRAFANA_HEALTH = "http://127.0.0.1:3000/api/health";
    private static final String GRAFANA_DASHBOARD_URL = "http://localhost:3000/d/infinito-monitoreo";

    private final HealthChecker healthChecker = new HealthChecker();
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "monitoreo-status-poll");
                t.setDaemon(true);
                return t;
            });

    /** "starting" se setea durante un start*() y se baja en cuanto se ve un health 200 o expira el timeout. */
    private volatile boolean influxStarting  = false;
    private volatile boolean grafanaStarting = false;

    private volatile ServiceStatus influxStatus  = ServiceStatus.NOT_RUNNING;
    private volatile ServiceStatus grafanaStatus = ServiceStatus.NOT_RUNNING;

    public MonitoreoManager() {
        monitor.scheduleAtFixedRate(this::refresh, 0, 2, TimeUnit.SECONDS);
    }

    // ============== Status ==================================================

    public ServiceStatus getInfluxStatus()  { return influxStatus;  }
    public ServiceStatus getGrafanaStatus() { return grafanaStatus; }

    public boolean isInfluxHealthy()  { return healthChecker.isHttpHealthy(INFLUX_HEALTH);  }
    public boolean isGrafanaHealthy() { return healthChecker.isHttpHealthy(GRAFANA_HEALTH); }

    private void refresh() {
        // Influx
        boolean h = healthChecker.isHttpHealthy(INFLUX_HEALTH);
        if (h) {
            influxStatus = ServiceStatus.RUNNING;
            influxStarting = false;
        } else if (influxStarting) {
            influxStatus = ServiceStatus.STARTING;
        } else {
            influxStatus = ServiceStatus.NOT_RUNNING;
        }
        // Grafana
        h = healthChecker.isHttpHealthy(GRAFANA_HEALTH);
        if (h) {
            grafanaStatus = ServiceStatus.RUNNING;
            grafanaStarting = false;
        } else if (grafanaStarting) {
            grafanaStatus = ServiceStatus.STARTING;
        } else {
            grafanaStatus = ServiceStatus.NOT_RUNNING;
        }
    }

    // ============== Acciones por servicio ===================================

    /** Bloqueante: invoca el script y devuelve cuando termina. */
    public ScriptResult startInflux()    { influxStarting  = true; return runScript("start-influx.ps1"); }
    public ScriptResult stopInflux()     { influxStarting  = false; return runScript("stop-influx.ps1"); }
    public ScriptResult restartInflux()  { influxStarting  = true; return runScript("restart-influx.ps1"); }

    public ScriptResult startGrafana()   { grafanaStarting = true; return runScript("start-grafana-bg.ps1"); }
    public ScriptResult stopGrafana()    { grafanaStarting = false; return runScript("stop-grafana.ps1"); }
    public ScriptResult restartGrafana() { grafanaStarting = true; return runScript("restart-grafana.ps1"); }

    /** Versiones async para no bloquear el hilo de UI (devuelven inmediatamente). */
    public void startInfluxAsync(Consumer<ScriptResult> cb)   { runAsync(() -> startInflux(), cb); }
    public void stopInfluxAsync(Consumer<ScriptResult> cb)    { runAsync(() -> stopInflux(), cb); }
    public void restartInfluxAsync(Consumer<ScriptResult> cb) { runAsync(() -> restartInflux(), cb); }

    public void startGrafanaAsync(Consumer<ScriptResult> cb)   { runAsync(() -> startGrafana(), cb); }
    public void stopGrafanaAsync(Consumer<ScriptResult> cb)    { runAsync(() -> stopGrafana(), cb); }
    public void restartGrafanaAsync(Consumer<ScriptResult> cb) { runAsync(() -> restartGrafana(), cb); }

    /** Diagnostico end-to-end (puertos + endpoints + database + datasource + dashboard). */
    public void runDiagnosticoAsync(Consumer<ScriptResult> cb) {
        runAsync(() -> runScript("healthcheck.ps1"), cb);
    }

    /**
     * Iniciar el stack completo: PRIMERO InfluxDB (porque crea la database y
     * el datasource de Grafana se conecta a ella en el primer arranque),
     * LUEGO Grafana. Bloqueante - lo invoca {@link ServiceManager#startAllSequential}.
     */
    public ScriptResult startAll() {
        ScriptResult r1 = startInflux();
        if (!r1.isOk()) return r1;
        ScriptResult r2 = startGrafana();
        return r2.isOk() ? r1 : r2;
    }

    /** Detener todo el stack. Idempotente. */
    public ScriptResult stopAll() {
        stopGrafana();
        return stopInflux();
    }

    // ============== Acciones de browser =====================================

    /** Abre el dashboard "Infinito - Monitoreo" directamente (sin pasar por menus). */
    public void openGrafanaDashboard() {
        try {
            // Intenta primero Chrome en modo --app (sin chrome del navegador)
            new ProcessBuilder("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "--app=" + GRAFANA_DASHBOARD_URL).start();
        } catch (IOException e1) {
            try {
                new ProcessBuilder("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                        "--app=" + GRAFANA_DASHBOARD_URL).start();
            } catch (IOException e2) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try { Desktop.getDesktop().browse(URI.create(GRAFANA_DASHBOARD_URL)); }
                    catch (Exception ignored) {}
                }
            }
        }
    }

    // ============== Lifecycle ===============================================

    public void shutdown() {
        monitor.shutdownNow();
    }

    // ============== Internals ===============================================

    /** Resolver el path del script en logs-infinito/scripts/ relativo al basePath. */
    private Path scriptPath(String name) {
        String base = AppConfig.getInstance().getBasePath();
        return Path.of(base, "logs-infinito", "scripts", name);
    }

    private ScriptResult runScript(String name) {
        Path script = scriptPath(name);
        if (!Files.exists(script)) {
            return new ScriptResult(-1, "{\"status\":\"fail\",\"reason\":\"script_not_found\"}",
                    "Script no encontrado: " + script);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toString()
            );
            pb.directory(script.getParent().getParent().toFile()); // logs-infinito/
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder full = new StringBuilder();
            String lastLine = "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    full.append(line).append('\n');
                    String t = line.trim();
                    if (!t.isEmpty()) lastLine = t;
                }
            }
            int exit = p.waitFor();
            return new ScriptResult(exit, lastLine, full.toString());
        } catch (Exception e) {
            return new ScriptResult(-1,
                    "{\"status\":\"fail\",\"reason\":\"" + e.getMessage().replace("\"","\\\"") + "\"}",
                    "Excepcion ejecutando " + name + ": " + e.getMessage());
        }
    }

    private void runAsync(java.util.function.Supplier<ScriptResult> action, Consumer<ScriptResult> cb) {
        Thread t = new Thread(() -> {
            ScriptResult r = action.get();
            if (cb != null) cb.accept(r);
        }, "monitoreo-action");
        t.setDaemon(true);
        t.start();
    }

    /** Resultado de invocar un script PowerShell. */
    public static class ScriptResult {
        public final int exitCode;
        /** Ultima linea no vacia (deberia ser JSON). */
        public final String jsonSummary;
        /** Output completo (stdout+stderr) para mostrar en panel de logs. */
        public final String fullOutput;

        public ScriptResult(int exit, String json, String full) {
            this.exitCode = exit; this.jsonSummary = json; this.fullOutput = full;
        }
        public boolean isOk() { return exitCode == 0; }
    }
}
