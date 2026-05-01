package com.infinitesoft.launcher.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Gestiona InfluxDB 3 Core del stack de monitoreo (logs-infinito).
 *
 * <p>NO arranca el proceso directamente: invoca los scripts PowerShell
 * publicados en {@code logs-infinito/scripts/} (contrato documentado en
 * {@code logs-infinito/docs/contrato-launcher.md}). Cada script es
 * idempotente, devuelve exit code claro y una linea JSON al final.
 *
 * <p>El status RUNNING/STARTING/NOT_RUNNING se calcula con polling
 * HTTP nativo (no via script) cada 2s, igual patron que {@link ServiceManager}.
 *
 * <p>Grafana fue eliminado del stack de distribucion en v2. La
 * visualizacion de logs se realiza desde el componente Angular nativo.
 */
public class MonitoreoManager {

    public static final String NAME_INFLUX = "monitoreo-influx";

    private static final String INFLUX_HEALTH = "http://127.0.0.1:8181/health";

    private final HealthChecker healthChecker = new HealthChecker();
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "monitoreo-status-poll");
                t.setDaemon(true);
                return t;
            });

    /** "starting" se setea durante startInflux() y se baja cuando /health responde. */
    private volatile boolean influxStarting = false;

    private volatile ServiceStatus influxStatus = ServiceStatus.NOT_RUNNING;

    public MonitoreoManager() {
        monitor.scheduleAtFixedRate(this::refresh, 0, 2, TimeUnit.SECONDS);
    }

    // ============== Status ==================================================

    public ServiceStatus getInfluxStatus() { return influxStatus; }

    public boolean isInfluxHealthy() { return healthChecker.isHttpHealthy(INFLUX_HEALTH); }

    private void refresh() {
        boolean h = healthChecker.isHttpHealthy(INFLUX_HEALTH);
        if (h) {
            influxStatus = ServiceStatus.RUNNING;
            influxStarting = false;
        } else if (influxStarting) {
            influxStatus = ServiceStatus.STARTING;
        } else {
            influxStatus = ServiceStatus.NOT_RUNNING;
        }
    }

    // ============== Acciones ================================================

    /** Bloqueante: invoca el script y devuelve cuando termina. */
    public ScriptResult startInflux()   { influxStarting = true;  return runScript("start-influx.ps1"); }
    public ScriptResult stopInflux()    { influxStarting = false; return runScript("stop-influx.ps1"); }
    public ScriptResult restartInflux() { influxStarting = true;  return runScript("restart-influx.ps1"); }

    /** Versiones async para no bloquear el hilo de UI. */
    public void startInfluxAsync(Consumer<ScriptResult> cb)   { runAsync(this::startInflux, cb); }
    public void stopInfluxAsync(Consumer<ScriptResult> cb)    { runAsync(this::stopInflux, cb); }
    public void restartInfluxAsync(Consumer<ScriptResult> cb) { runAsync(this::restartInflux, cb); }

    /** Diagnostico: healthcheck.ps1 verifica puerto 8181, /health y database infinito_logs. */
    public void runDiagnosticoAsync(Consumer<ScriptResult> cb) {
        runAsync(() -> runScript("healthcheck.ps1"), cb);
    }

    /**
     * Arrancar InfluxDB. Bloqueante - invocado por {@link ServiceManager#startAllSequential}.
     */
    public ScriptResult startAll() {
        return startInflux();
    }

    /** Detener InfluxDB. Idempotente. */
    public ScriptResult stopAll() {
        return stopInflux();
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
                    "{\"status\":\"fail\",\"reason\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}",
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
            this.exitCode = exit;
            this.jsonSummary = json;
            this.fullOutput = full;
        }

        public boolean isOk() { return exitCode == 0; }
    }
}
