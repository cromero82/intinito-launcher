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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ServiceManager {
    private final Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Path> processLogFiles = new ConcurrentHashMap<>();
    private final Map<String, ServiceStatus> statuses = new ConcurrentHashMap<>();
    private final HealthChecker healthChecker = new HealthChecker();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService startExecutor = Executors.newCachedThreadPool();

    public static final String NAME_DB = "db";
    public static final String NAME_SECURITY = "security";
    public static final String NAME_SMTP = "smtp";
    public static final String NAME_STORE = "store";
    public static final String NAME_FRONT = "front";
    public static final String MICOTIZACION_PATH = "/apps/personas/micotizacion";
    private static final int FRONTEND_HEALTH_PORT = 3001;
    private static final int CADDY_PORT = 8080;
    private static final Pattern TUNNEL_URL_PATTERN =
            Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Path LOGS_DIRECTORY = Path.of(System.getProperty("user.home"), ".infinitesoft/logs");
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String JAVA_BIN_DIR = discoverJavaBinDir();
    private static final String MAVEN_CMD = discoverMvnCommand();


    public ServiceManager() {
        ensureLogsDirectoryExists();

        String base = AppConfig.getInstance().getBasePath();

        // DB via Docker container id
        definitions.put(NAME_DB, new ServiceDefinition(
                "Base de Datos (Postgres - Docker)",
                ServiceType.DOCKER,
                Path.of(base),
                "docker start postgres-pos",
                "docker stop postgres-pos",
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
        String frontStartCommand = "npm run serve:prod"; // Default for Linux
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            frontStartCommand = "npm run start:micotizacion";
        }

        definitions.put(NAME_FRONT, new ServiceDefinition(
                "Aplicación Frontend",
                ServiceType.NODE_NPM,
                Path.of(base, "infinito-ai-front"),
                frontStartCommand,
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

    @SuppressWarnings("unused")
    private void ensureFirewallRuleExists() {
        // No aplica en Linux; se elimina la llamada desde el constructor.
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
        startExecutor.execute(() -> {
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
            try {
                String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP_FORMATTER);
                String logFileName = String.format("%s-%s.log", key, timestamp);
                Path logFilePath = LOGS_DIRECTORY.resolve(logFileName);

                String javaCmd = (JAVA_BIN_DIR != null) ? JAVA_BIN_DIR + File.separator + "java" : "java";
                String fullCommand = def.getStartCommand().replaceFirst("^java\\s", javaCmd + " ");

                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        fullCommand + " > " + logFilePath.toAbsolutePath() + " 2>&1")
                        .directory(def.getWorkingDir().toFile());

                applyShellPath(pb, JAVA_BIN_DIR);

                Process process = pb.start();
                processes.put(key, process);
                processLogFiles.put(key, logFilePath);
            } catch (IOException e) {
                statuses.put(key, ServiceStatus.FAILED);
                e.printStackTrace();
            }
        });
    }

    public void stop(String key) {
        ServiceDefinition def = definitions.get(key);
        if (def == null) return;

        if (def.getType() == ServiceType.DOCKER && def.getStopCommand() != null) {
            System.out.println("Deteniendo contenedor Docker: " + def.getStopCommand());
            try {
                new ProcessBuilder("sh", "-c", def.getStopCommand()).start();
            } catch (IOException e) {
                System.err.println("Error al detener contenedor Docker: " + e.getMessage());
            }
        } else if (key.equals(NAME_FRONT)) {
            boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
            if (isMac) {
                System.out.println("Deteniendo servicio Frontend en puertos " + def.getTcpPort() + ", "
                        + FRONTEND_HEALTH_PORT + " y " + CADDY_PORT);
                killProcessOnPort(def.getTcpPort());
                killProcessOnPort(FRONTEND_HEALTH_PORT);
                killProcessOnPort(CADDY_PORT);
                killProcessByPattern("cloudflared tunnel");
                killProcessByPattern("caddy run");
            } else {
                System.out.println("Deteniendo servicio Frontend en puertos " + def.getTcpPort() + " y " + FRONTEND_HEALTH_PORT);
                killProcessOnPort(def.getTcpPort());
                killProcessOnPort(FRONTEND_HEALTH_PORT);
            }
        } else if (def.getTcpPort() != null && (def.getType() == ServiceType.NODE_NPM || def.getType() == ServiceType.JAVA_JAR)) {
            System.out.println("Deteniendo servicio " + def.getName() + " en puerto " + def.getTcpPort());
            killProcessOnPort(def.getTcpPort());
        }

        Process p = processes.remove(key);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        processLogFiles.remove(key);
        statuses.put(key, ServiceStatus.NOT_RUNNING);
    }

    public Optional<String> findTunnelBaseUrl() {
        Path logPath = processLogFiles.get(NAME_FRONT);
        if (logPath == null) {
            logPath = findLatestLogForService(NAME_FRONT);
        }
        if (logPath == null || !Files.exists(logPath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(logPath);
            Matcher matcher = TUNNEL_URL_PATTERN.matcher(content);
            String lastMatch = null;
            while (matcher.find()) {
                lastMatch = matcher.group();
            }
            return Optional.ofNullable(lastMatch);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<String> findMicotizacionTunnelUrl() {
        return findTunnelBaseUrl().map(base -> base + MICOTIZACION_PATH);
    }

    public Optional<String> waitForTunnelUrl(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<String> url = findMicotizacionTunnelUrl();
            if (url.isPresent()) {
                return url;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return findMicotizacionTunnelUrl();
    }

    public void redeployFrontTunnelStack() {
        startExecutor.execute(() -> {
            stop(NAME_FRONT);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            start(NAME_FRONT);
        });
    }

    public void ensurePosServicesRunning() {
        start(NAME_SECURITY);
        start(NAME_STORE);
        start(NAME_FRONT);
    }

    private Path findLatestLogForService(String serviceKey) {
        try (var stream = Files.list(LOGS_DIRECTORY)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(serviceKey + "-"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void applyShellPath(ProcessBuilder pb, String javaBinDir) {
        Map<String, String> env = pb.environment();
        String currentPath = env.get("PATH");
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            String macPaths = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin";
            String combined = (currentPath != null ? currentPath + File.pathSeparator : "") + macPaths;
            if (javaBinDir != null) {
                combined = javaBinDir + File.pathSeparator + combined;
            }
            env.put("PATH", combined);
        } else if (javaBinDir != null) {
            env.put("PATH", javaBinDir + File.pathSeparator + (currentPath != null ? currentPath : ""));
        }
        if (javaBinDir != null) {
            env.put("JAVA_HOME", Path.of(javaBinDir).getParent().toString());
        }
    }

    private void killProcessByPattern(String pattern) {
        try {
            new ProcessBuilder("sh", "-c", "pkill -f '" + pattern.replace("'", "'\\''") + "'").start();
        } catch (IOException e) {
            System.err.println("Error al detener procesos con patrón " + pattern + ": " + e.getMessage());
        }
    }

    public void restart(String key) {
        startExecutor.execute(() -> {
            stop(key);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            start(key);
        });
    }

    public void startAll() {
        getServiceKeysInOrder().stream().filter(key -> !key.equals(NAME_DB)).forEach(this::start);
    }

    public void stopAll() {
        new ArrayList<>(definitions.keySet()).stream().filter(key -> !key.equals(NAME_DB)).forEach(this::stop);
    }

    public void updateProject(String key, java.util.function.Consumer<String> logCallback) {
        ServiceDefinition def = definitions.get(key);
        if (def == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                logCallback.accept(">>> git pull");
                runAndLogShell("git pull", def.getWorkingDir().toFile(), logCallback, null);

                if (def.getType() == ServiceType.JAVA_JAR) {
                    logCallback.accept("\n>>> mvn package -DskipTests");
                    runAndLogShell(MAVEN_CMD + " package -DskipTests", def.getWorkingDir().toFile(), logCallback, JAVA_BIN_DIR);
                } else if (def.getType() == ServiceType.NODE_NPM) {
                    logCallback.accept("\n>>> npm install");
                    runAndLogShell("npm install", def.getWorkingDir().toFile(), logCallback, null);
                    logCallback.accept("\n>>> npm run build  (ng build --configuration production)");
                    runAndLogShell("npm run build", def.getWorkingDir().toFile(), logCallback, null);
                }
                logCallback.accept("\n=== Proceso completado ===");
            } catch (Exception e) {
                logCallback.accept("ERROR: " + e.getMessage());
            }
        });
    }

    private void runAndLogShell(String command, java.io.File workingDir, java.util.function.Consumer<String> logCallback, String javaBinDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                .directory(workingDir)
                .redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        String currentPath = env.get("PATH");

        // macOS: apps GUI no heredan PATH del shell, agregar rutas estándar
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            String macPaths = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin";
            String combined = (currentPath != null ? currentPath + File.pathSeparator : "") + macPaths;
            if (javaBinDir != null) {
                combined = javaBinDir + File.pathSeparator + combined;
            }
            env.put("PATH", combined);
        } else if (javaBinDir != null) {
            env.put("PATH", javaBinDir + File.pathSeparator + (currentPath != null ? currentPath : ""));
        }

        if (javaBinDir != null) {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome == null && JAVA_BIN_DIR != null) {
                env.put("JAVA_HOME", Path.of(JAVA_BIN_DIR).getParent().toString());
            }
        }

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logCallback.accept(line);
            }
        }
        process.waitFor();
    }

    private void runAndLog(String[] command, java.io.File workingDir, java.util.function.Consumer<String> logCallback, String javaBinDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        if (javaBinDir != null) {
            String currentPath = pb.environment().get("PATH");
            pb.environment().put("PATH", javaBinDir + File.pathSeparator + (currentPath != null ? currentPath : ""));
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome == null && JAVA_BIN_DIR != null) {
                pb.environment().put("JAVA_HOME", Path.of(JAVA_BIN_DIR).getParent().toString());
            }
        }
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logCallback.accept(line);
            }
        }
        process.waitFor();
    }

    private static String discoverMvnCommand() {
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null) {
            Path mvn = Path.of(mavenHome, "bin", "mvn");
            if (Files.exists(mvn)) return mvn.toAbsolutePath().toString();
        }
        Path m2Dir = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (Files.isDirectory(m2Dir)) {
            try (var stream = Files.walk(m2Dir, 6)) {
                Optional<Path> found = stream
                        .filter(p -> p.endsWith("bin/mvn") && Files.isExecutable(p))
                        .findFirst();
                if (found.isPresent()) return found.get().toAbsolutePath().toString();
            } catch (IOException ignored) {}
        }
        return "mvn";
    }

    private static String discoverJavaBinDir() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path binDir = Path.of(javaHome, "bin");
            if (Files.isDirectory(binDir) && Files.exists(binDir.resolve("java"))) {
                return binDir.toAbsolutePath().toString();
            }
        }
        
        String osName = System.getProperty("os.name").toLowerCase();
        // macOS: Check user library first, then system library
        if (osName.contains("mac")) {
            List<Path> macJVMPaths = List.of(
                Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
                Path.of("/Library/Java/JavaVirtualMachines")
            );
            for (Path macJVMs : macJVMPaths) {
                if (Files.isDirectory(macJVMs)) {
                    try (var stream = Files.list(macJVMs)) {
                        List<Path> jdks = stream
                            .filter(Files::isDirectory)
                            .sorted(Comparator.reverseOrder())
                            .collect(Collectors.toList());
                        for (Path jdk : jdks) {
                            Path binDir = jdk.resolve("Contents/Home/bin");
                            if (Files.isDirectory(binDir) && Files.exists(binDir.resolve("java"))) {
                                return binDir.toAbsolutePath().toString();
                            }
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
        
        Path jdksDir = Path.of(System.getProperty("user.home"), ".jdks");
        if (Files.isDirectory(jdksDir)) {
            try (var stream = Files.list(jdksDir)) {
                List<Path> jdks = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                for (Path jdk : jdks) {
                    Path binDir = jdk.resolve("bin");
                    if (Files.isDirectory(binDir) && Files.exists(binDir.resolve("java"))) {
                        return binDir.toAbsolutePath().toString();
                    }
                }
            } catch (IOException ignored) {}
        }
        
        // Linux: Check /usr/lib/jvm
        Path usrJvm = Path.of("/usr/lib/jvm");
        if (Files.isDirectory(usrJvm)) {
            try (var stream = Files.list(usrJvm)) {
                List<Path> jdks = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                for (Path jdk : jdks) {
                    Path binDir = jdk.resolve("bin");
                    if (Files.isDirectory(binDir) && Files.exists(binDir.resolve("java"))) {
                        return binDir.toAbsolutePath().toString();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    public void shutdown() {
        System.out.println("Cerrando launcher - los servicios permanecen activos.");
        monitor.shutdownNow();
        System.out.println("Launcher cerrado.");
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
            // Try lsof first (works on both macOS and Linux)
            Process finder = new ProcessBuilder("sh", "-c", "lsof -ti :" + port).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(finder.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        long targetPid = Long.parseLong(line);
                        if (targetPid == ownPid) {
                            System.err.println("ADVERTENCIA: Se ha evitado la auto-terminación del launcher (PID: " + targetPid + ") en el puerto " + port);
                            continue;
                        }
                        System.out.println("Deteniendo proceso con PID: " + targetPid + " en el puerto " + port);
                        new ProcessBuilder("kill", "-9", String.valueOf(targetPid)).start();
                    } catch (NumberFormatException | IOException e) {
                        // Ignorar
                    }
                }
            }
            finder.waitFor();
            
            // If lsof didn't find anything, try fuser (Linux alternative)
            // This is a fallback and won't affect macOS
        } catch (IOException | InterruptedException e) {
            // Fallback for macOS where lsof might need different permissions
            try {
                Process pkill = new ProcessBuilder("sh", "-c", "pkill -f ':" + port + "'").start();
                pkill.waitFor();
            } catch (Exception ex) {
                System.err.println("Error al intentar detener proceso en puerto " + port + ": " + ex.getMessage());
            }
        }
    }
}
