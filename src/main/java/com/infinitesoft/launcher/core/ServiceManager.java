package com.infinitesoft.launcher.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServiceManager {
    private final Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, ServiceStatus> statuses = new ConcurrentHashMap<>();
    private final HealthChecker healthChecker = new HealthChecker();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

    public static final String NAME_DB = "db";
    public static final String NAME_SECURITY = "security";
    public static final String NAME_SMTP = "smtp";
    public static final String NAME_STORE = "store";
    public static final String NAME_FRONT = "front";

    public ServiceManager() {
        String base = "C:/dev/repos";

        // DB via Docker container id
        definitions.put(NAME_DB, new ServiceDefinition(
                "Base de Datos (Postgres - Docker)",
                ServiceType.DOCKER,
                Path.of(base),
                "docker start 8be3920261a9",
                "docker stop 8be3920261a9",
                null,
                5432,
                1
        ));

        // Security (8081)
        definitions.put(NAME_SECURITY, new ServiceDefinition(
                "Servicio de Seguridad",
                ServiceType.JAVA_JAR,
                Path.of(base, "infinito-security"),
                String.join(" ",
                        "java -jar",
                        "target/infinito-security-0.0.1-SNAPSHOT.jar",
                        "--server.port=8081",
                        "\"--spring.datasource.url=jdbc:postgresql://localhost:5432/controlneg_rmx_db?currentSchema=security\"",
                        "--spring.datasource.username=romax-admin",
                        "\"--spring.datasource.password=f4ast3rv3rs10n*\""
                ),
                null,
                "http://localhost:8081/actuator/health",
                8081,
                2
        ));

        // SMTP (8082)
        definitions.put(NAME_SMTP, new ServiceDefinition(
                "Servicio de Correos (SMTP)",
                ServiceType.JAVA_JAR,
                Path.of(base, "infinito-smtp-service"),
                String.join(" ",
                        "java -jar",
                        "target/smtp-service-0.0.1-SNAPSHOT.jar",
                        "--spring.mail.host=smtp.gmail.com",
                        "--spring.mail.port=587",
                        "--spring.mail.username=romeromailercarlos@gmail.com",
                        "\"--spring.mail.password=calccpheylhharyd\"",
                        "--spring.mail.properties.mail.smtp.auth=true",
                        "--spring.mail.properties.mail.smtp.starttls.enable=true",
                        "--app.mail.from=romeromailercarlos@gmail.com"
                ),
                null,
                "http://localhost:8082/actuator/health",
                8082,
                3
        ));

        // Store (8088)
        definitions.put(NAME_STORE, new ServiceDefinition(
                "Servicio Lógica Tienda",
                ServiceType.JAVA_JAR,
                Path.of(base, "pos-relational-data-service"),
                "java -jar target/pos-relational-data-service-0.0.1-SNAPSHOT.jar --logging.file.name=logica-store.log --server.port=8088",
                null,
                "http://localhost:8088/actuator/health",
                8088,
                4
        ));

        // Front (3001)
        definitions.put(NAME_FRONT, new ServiceDefinition(
                "Aplicación Frontend",
                ServiceType.NODE_NPM,
                Path.of(base, "infinito-ai-front"),
                "npm start",
                null,
                "http://localhost:3001/actuator/health",
                3001,
                5
        ));

        // init statuses
        definitions.keySet().forEach(k -> statuses.put(k, ServiceStatus.NOT_RUNNING));

        // start monitor loop
        monitor.scheduleAtFixedRate(this::refreshStatuses, 0, 2, TimeUnit.SECONDS);
    }

    public List<String> getServiceKeysInOrder() {
        return definitions.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue().getOrder()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public Map<String, ServiceStatus> snapshotStatuses() {
        return new LinkedHashMap<>(statuses);
    }

    public Optional<ServiceDefinition> getDefinition(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    public void startAllSequential(Duration perStepTimeout) {
        Executors.newSingleThreadExecutor().execute(() -> {
            for (String key : getServiceKeysInOrder()) {
                start(key);
                // Wait until healthy or timeout before starting next
                long deadline = System.currentTimeMillis() + perStepTimeout.toMillis();
                while (System.currentTimeMillis() < deadline) {
                    if (statuses.getOrDefault(key, ServiceStatus.NOT_RUNNING) == ServiceStatus.RUNNING) break;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    public void start(String key) {
        ServiceDefinition def = definitions.get(key);
        if (def == null) return;
        // Si ya está saludable (aunque no lo hayamos lanzado nosotros), no iniciar de nuevo
        boolean alreadyHealthy = false;
        if (def.getHealthUrl() != null) {
            alreadyHealthy = healthChecker.isHttpHealthy(def.getHealthUrl());
        } else if (def.getTcpPort() != null) {
            alreadyHealthy = healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000);
        }
        if (alreadyHealthy) {
            statuses.put(key, ServiceStatus.RUNNING);
            return;
        }
        if (processes.containsKey(key) && processes.get(key).isAlive()) {
            statuses.put(key, ServiceStatus.RUNNING);
            return;
        }
        statuses.put(key, ServiceStatus.STARTING);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Process process;
                ServiceType type = def.getType();
                if (type == ServiceType.DOCKER || type == ServiceType.NODE_NPM || type == ServiceType.JAVA_JAR) {
                    process = new ProcessBuilder(commandForShell(def.getName(), def.getStartCommand()))
                            .directory(new File(def.getWorkingDir().toString()))
                            .start();
                } else {
                    throw new IllegalStateException("Tipo no soportado");
                }
                processes.put(key, process);
            } catch (IOException e) {
                statuses.put(key, ServiceStatus.FAILED);
            }
        });
    }

    public void stop(String key) {
        ServiceDefinition def = definitions.get(key);
        if (def == null) return;
        try {
            if (def.getType() == ServiceType.DOCKER && def.getStopCommand() != null) {
                new ProcessBuilder(commandForShell(def.getName(), def.getStopCommand()))
                        .directory(new File(def.getWorkingDir().toString()))
                        .start();
            } else {
                // Para procesos con ventana CMD abierta, intentar cerrar por título y su árbol
                killByWindowTitle(def.getName());
            }
        } catch (IOException ignored) {}
        Process p = processes.remove(key);
        if (p != null && p.isAlive()) {
            // Intento amable primero
            p.destroy();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (p.isAlive()) {
                // Forzar si sigue con vida
                p.destroyForcibly();
            }
        }
        statuses.put(key, ServiceStatus.NOT_RUNNING);
    }

    public void restart(String key) {
        stop(key);
        // pequeña espera
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        start(key);
    }

    public void startAll() {
        getServiceKeysInOrder().forEach(this::start);
    }

    public void stopAll() {
        new ArrayList<>(definitions.keySet()).forEach(this::stop);
    }

    private void refreshStatuses() {
        definitions.forEach((key, def) -> {
            ServiceStatus s = computeStatus(key, def);
            statuses.put(key, s);
        });
    }

    private ServiceStatus computeStatus(String key, ServiceDefinition def) {
        // If we have a process and it's alive, consider STARTING/RUNNING depending on health
        boolean alive = Optional.ofNullable(processes.get(key)).map(Process::isAlive).orElse(false);

        boolean healthy = false;
        if (def.getHealthUrl() != null) {
            healthy = healthChecker.isHttpHealthy(def.getHealthUrl());
        } else if (def.getTcpPort() != null) {
            healthy = healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000);
        }

        if (healthy) return ServiceStatus.RUNNING;
        if (alive) return ServiceStatus.STARTING;
        // For docker DB, even if process not tracked (docker is external), consider TCP
        if (def.getType() == ServiceType.DOCKER && def.getTcpPort() != null) {
            if (healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000)) return ServiceStatus.RUNNING;
        }
        return ServiceStatus.NOT_RUNNING;
    }

    // Creates a Windows-friendly command via cmd.exe /c start "<title>" <command> (detached)
    private List<String> commandForShell(String windowTitle, String command) {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd.exe");
        cmd.add("/c");
        String safeTitle = makeSafeTitle(windowTitle);
        cmd.add("start \"" + safeTitle + "\" " + command);
        return cmd;
    }

    // Normaliza el título de ventana para Windows CMD
    private String makeSafeTitle(String windowTitle) {
        return (windowTitle == null || windowTitle.isBlank())
                ? "svc"
                : windowTitle.replace('"', '\'');
    }

    // Intenta cerrar la ventana de consola por título y su árbol de procesos (Windows)
    private void killByWindowTitle(String windowTitle) {
        String safeTitle = makeSafeTitle(windowTitle);
        try {
            // taskkill con filtro por título de ventana y cierre forzado del árbol
            // Importante: el valor de /FI debe ir entre comillas para títulos con espacios
            String cmd = "taskkill /F /T /FI \"WINDOWTITLE eq " + safeTitle + "\"";
            new ProcessBuilder("cmd.exe", "/c", cmd).start();
        } catch (IOException ignored) {}
    }
}
