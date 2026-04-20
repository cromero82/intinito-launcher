package com.infinitesoft.launcher.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private static final int FRONTEND_HEALTH_PORT = 3001;
    private static final Path LOGS_DIRECTORY = Path.of("C:/dev/repos/intinito-launcher/logs");
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");


    public ServiceManager() {
        // Asegurar que la regla del firewall para Java exista.
        ensureFirewallRuleExists();
        ensureLogsDirectoryExists();

        String base = "C:/dev/repos";

        // DB via Docker container id
        definitions.put(NAME_DB, new ServiceDefinition(
                "Base de Datos (Postgres - Docker)",
                ServiceType.DOCKER,
                Path.of(base),
                null,
                null,
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
                        "--server.port=8082",
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
                "java -jar target/pos-relational-data-service-0.0.1-SNAPSHOT.jar --server.port=8088",
                null,
                "http://localhost:8088/actuator/health",
                8088,
                4
        ));

        // Front (4200)
        definitions.put(NAME_FRONT, new ServiceDefinition(
                "Aplicación Frontend",
                ServiceType.NODE_NPM,
                Path.of(base, "infinito-ai-front"),
                "npm start",
                null,
                "http://127.0.0.1:" + FRONTEND_HEALTH_PORT + "/actuator/health",
                4200,
                5
        ));

        // init statuses
        definitions.keySet().forEach(k -> statuses.put(k, ServiceStatus.NOT_RUNNING));

        // start monitor loop
        monitor.scheduleAtFixedRate(this::refreshStatuses, 0, 2, TimeUnit.SECONDS);
    }

    private void ensureLogsDirectoryExists() {
        try {
            Files.createDirectories(LOGS_DIRECTORY);
        } catch (IOException e) {
            System.err.println("No se pudo crear el directorio de logs: " + LOGS_DIRECTORY);
            e.printStackTrace();
        }
    }

    private void ensureFirewallRuleExists() {
        final String ruleName = "Java Launcher (infinito-launcher)";
        try {
            String checkCommand = "Get-NetFirewallRule -DisplayName '" + ruleName + "' -ErrorAction SilentlyContinue";
            ProcessBuilder checkProcessBuilder = new ProcessBuilder("powershell.exe", "-Command", checkCommand);
            Process pCheck = checkProcessBuilder.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(pCheck.getInputStream()));
            String line = reader.readLine();
            pCheck.waitFor();

            if (line != null && !line.isBlank()) {
                System.out.println("La regla del firewall '" + ruleName + "' ya existe.");
                return;
            }

            System.out.println("La regla del firewall no existe. Intentando crearla...");
            String javaPath = ProcessHandle.current().info().command().orElse("java.exe").replace("'", "''");
            
            String createCommand = "New-NetFirewallRule -DisplayName '" + ruleName + "' -Direction Outbound -Program '" + javaPath + "' -Action Allow";
            
            String fullCommand = "Start-Process powershell.exe -ArgumentList '-NoProfile -ExecutionPolicy Bypass -Command \"" + createCommand.replace("\"", "\\\"") + "\"' -Verb RunAs";

            ProcessBuilder createProcess = new ProcessBuilder("powershell.exe", "-Command", fullCommand);
            createProcess.start();
            System.out.println("Se ha solicitado la creación de la regla del firewall. Por favor, acepte la solicitud de UAC.");

        } catch (IOException | InterruptedException e) {
            System.err.println("Error al verificar o crear la regla del firewall: " + e.getMessage());
            e.printStackTrace();
        }
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
                if (key.equals(NAME_DB)) continue;
                start(key);
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
        if (def == null || def.getStartCommand() == null) return;
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
                String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP_FORMATTER);
                String logFileName = String.format("%s-%s.log", key, timestamp);
                Path logFilePath = LOGS_DIRECTORY.resolve(logFileName);

                String commandWithRedirection = String.format("start /B %s > \"%s\" 2>&1",
                        def.getStartCommand(), logFilePath.toAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", commandWithRedirection)
                        .directory(def.getWorkingDir().toFile());

                Process process = pb.start();
                processes.put(key, process);
            } catch (IOException e) {
                statuses.put(key, ServiceStatus.FAILED);
                e.printStackTrace();
            }
        });
    }

    public void stop(String key) {
        ServiceDefinition def = definitions.get(key);
        if (def == null) return;

        if (key.equals(NAME_FRONT)) {
            System.out.println("Deteniendo servicio Frontend en puertos " + def.getTcpPort() + " y " + FRONTEND_HEALTH_PORT);
            killProcessOnPort(def.getTcpPort());
            killProcessOnPort(FRONTEND_HEALTH_PORT);
        } else if (def.getTcpPort() != null && (def.getType() == ServiceType.NODE_NPM || def.getType() == ServiceType.JAVA_JAR)) {
            System.out.println("Deteniendo servicio " + def.getName() + " en puerto " + def.getTcpPort());
            killProcessOnPort(def.getTcpPort());
        }

        Process p = processes.remove(key);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        statuses.put(key, ServiceStatus.NOT_RUNNING);
    }

    public void restart(String key) {
        stop(key);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        start(key);
    }

    public void startAll() {
        getServiceKeysInOrder().stream().filter(key -> !key.equals(NAME_DB)).forEach(this::start);
    }

    public void stopAll() {
        new ArrayList<>(definitions.keySet()).stream().filter(key -> !key.equals(NAME_DB)).forEach(this::stop);
    }

    public void shutdown() {
        System.out.println("Iniciando apagado completo de ServiceManager...");
        stopAll();
        monitor.shutdownNow(); // Detiene el hilo de monitoreo inmediatamente.
        System.out.println("ServiceManager apagado.");
    }

    private void refreshStatuses() {
        definitions.forEach((key, def) -> {
            ServiceStatus s = computeStatus(key, def);
            statuses.put(key, s);
        });
    }

    private ServiceStatus computeStatus(String key, ServiceDefinition def) {
        boolean alive = Optional.ofNullable(processes.get(key)).map(Process::isAlive).orElse(false);

        boolean healthy = false;
        if (def.getHealthUrl() != null) {
            healthy = healthChecker.isHttpHealthy(def.getHealthUrl());
        } else if (def.getTcpPort() != null) {
            healthy = healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000);
        }

        if (healthy) return ServiceStatus.RUNNING;
        if (alive) return ServiceStatus.STARTING;
        if (def.getType() == ServiceType.DOCKER && def.getTcpPort() != null) {
            if (healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000)) return ServiceStatus.RUNNING;
        }
        return ServiceStatus.NOT_RUNNING;
    }

    private void killProcessOnPort(int port) {
        long ownPid = ProcessHandle.current().pid();
        try {
            String command = String.format("netstat -ano | findstr :%d | findstr LISTENING", port);
            Process p = new ProcessBuilder("cmd.exe", "/c", command).start();
            
            new BufferedReader(new InputStreamReader(p.getInputStream())).lines().forEach(line -> {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    try {
                        long targetPid = Long.parseLong(parts[parts.length - 1]);
                        if (targetPid == ownPid) {
                            System.err.println("ADVERTENCIA: Se ha evitado la auto-terminación del launcher (PID: " + targetPid + ") en el puerto " + port);
                            return;
                        }
                        System.out.println("Deteniendo proceso con PID: " + targetPid + " en el puerto " + port);
                        new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(targetPid)).start();
                    } catch (NumberFormatException | IOException e) {
                        // Ignorar si la línea no es válida o hay un error al matar
                    }
                }
            });
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Error al intentar detener proceso en puerto " + port + ": " + e.getMessage());
        }
    }
}
