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
    private LaunchEnvironment environment;

    public static final String NAME_DB = "db";
    public static final String NAME_SECURITY = "security";
    public static final String NAME_SMTP = "smtp";
    public static final String NAME_STORE = "store";
    public static final String NAME_PUENTE = "puente";
    public static final String NAME_CADDY = "caddy";
    public static final String NAME_TUNNEL = "tunnel";
    public static final String NAME_FRONT = "front";
    public static final String MICOTIZACION_PATH = "/apps/personas/micotizacion";
    private static final long OWN_PID = ProcessHandle.current().pid();
    private static final Pattern TUNNEL_URL_PATTERN =
            Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Path LOGS_DIRECTORY = Path.of(System.getProperty("user.home"), ".infinitesoft/logs");
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String JAVA_BIN_DIR = discoverJavaBinDir();
    private static final String MAVEN_CMD = discoverMvnCommand();


    public ServiceManager() {
        ensureLogsDirectoryExists();
        this.environment = AppConfig.getInstance().getEnvironment();
        rebuildDefinitions();
        monitor.scheduleAtFixedRate(this::refreshStatuses, 0, 2, TimeUnit.SECONDS);
        startExecutor.execute(this::ensureTunnelAutostart);
    }

    public LaunchEnvironment getEnvironment() {
        return environment;
    }

    /**
     * Cambia ambiente (puertos, carpeta sandbox, hostname). Detiene lo que este launcher haya arrancado.
     */
    public synchronized void applyEnvironment(LaunchEnvironment env) {
        if (env == null) {
            env = LaunchEnvironment.DEV_LOCAL;
        }
        stopAll();
        this.environment = env;
        AppConfig.getInstance().setEnvironment(env);
        rebuildDefinitions();
        startExecutor.execute(this::ensureTunnelAutostart);
    }

    private void rebuildDefinitions() {
        definitions.clear();
        statuses.clear();
        Path base = Path.of(AppConfig.getInstance().getBasePath());
        Path root = environment.reposRoot(base);
        String profileArg = environment.springProfileArg();
        String profileSuffix = profileArg.isBlank() ? "" : " " + profileArg;

        definitions.put(NAME_DB, new ServiceDefinition(
                "Base de Datos (Postgres - Docker)",
                ServiceType.DOCKER,
                root,
                "docker start postgres-pos",
                "docker stop postgres-pos",
                null,
                5432,
                1
        ));

        definitions.put(NAME_SECURITY, new ServiceDefinition(
                "Servicio de Seguridad",
                ServiceType.JAVA_JAR,
                root.resolve("infinito-security"),
                String.join(" ",
                        "java -jar",
                        "target/infinito-security-0.0.1-SNAPSHOT.jar",
                        "--server.port=" + environment.getAuthPort(),
                        "\"--spring.datasource.url=" + environment.jdbcUrlAuth() + "\"",
                        "--spring.datasource.username=romax-admin",
                        "\"--spring.datasource.password=f4ast3rv3rs10n*\""
                ) + profileSuffix,
                null,
                "http://localhost:" + environment.getAuthPort() + "/actuator/health",
                environment.getAuthPort(),
                2
        ));

        definitions.put(NAME_SMTP, new ServiceDefinition(
                "Servicio de Correos (SMTP)",
                ServiceType.JAVA_JAR,
                root.resolve("infinito-smtp-service"),
                String.join(" ",
                        "java -jar",
                        "target/smtp-service-0.0.1-SNAPSHOT.jar",
                        "--server.port=" + environment.getSmtpPort(),
                        "--spring.mail.host=smtp.gmail.com",
                        "--spring.mail.port=587",
                        "--spring.mail.username=romeromailercarlos@gmail.com",
                        "\"--spring.mail.password=calccpheylhharyd\"",
                        "--spring.mail.properties.mail.smtp.auth=true",
                        "--spring.mail.properties.mail.smtp.starttls.enable=true",
                        "--app.mail.from=romeromailercarlos@gmail.com"
                ),
                null,
                "http://localhost:" + environment.getSmtpPort() + "/actuator/health",
                environment.getSmtpPort(),
                3
        ));

        definitions.put(NAME_STORE, new ServiceDefinition(
                "Servicio Lógica Tienda",
                ServiceType.JAVA_JAR,
                root.resolve("pos-relational-data-service"),
                String.join(" ",
                        "java -jar target/pos-relational-data-service-0.0.1-SNAPSHOT.jar",
                        "--server.port=" + environment.getStorePort(),
                        "\"--spring.datasource.url=" + environment.jdbcUrl(null) + "\""
                ) + profileSuffix,
                null,
                "http://localhost:" + environment.getStorePort() + "/actuator/health",
                environment.getStorePort(),
                4
        ));

        definitions.put(NAME_PUENTE, new ServiceDefinition(
                "Puente (notificaciones / correo banco)",
                ServiceType.JAVA_JAR,
                root.resolve("puente-tienda"),
                String.join(" ",
                        "java -jar target/puente-tienda-0.0.1-SNAPSHOT.jar",
                        "--server.port=" + environment.getPuentePort(),
                        "\"--spring.datasource.url=" + environment.jdbcUrl(null) + "\""
                ) + profileSuffix,
                null,
                "http://localhost:" + environment.getPuentePort() + "/actuator/health",
                environment.getPuentePort(),
                5
        ));

        String frontCmd;
        if (environment == LaunchEnvironment.SANDBOX) {
            frontCmd = "npx concurrently -k \"npm run health:server\" \"npm run start:angular:sandbox\"";
        } else if (environment == LaunchEnvironment.TIENDA_INFINITO) {
            frontCmd = "npx concurrently -k \"npm run health:server\" \"npm run start:angular:tienda-infinito\"";
        } else {
            frontCmd = "npm run start";
        }
        definitions.put(NAME_FRONT, new ServiceDefinition(
                "Aplicación Frontend",
                ServiceType.NODE_NPM,
                root.resolve("infinito-ai-front"),
                frontCmd,
                null,
                "http://127.0.0.1:" + environment.getHealthPort() + "/actuator/health",
                environment.getFrontPort(),
                6
        ));

        definitions.put(NAME_CADDY, new ServiceDefinition(
                "Caddy (portero local del túnel)",
                ServiceType.PROCESS,
                root.resolve("infinito-ai-front"),
                "caddy run --config " + environment.getCaddyfileName(),
                null,
                null,
                environment.getCaddyPort(),
                7
        ));

        definitions.put(NAME_TUNNEL, new ServiceDefinition(
                "Túnel Cloudflare (" + environment.getPublicHostname() + ")",
                ServiceType.PROCESS,
                root,
                tunnelStartCommand(),
                null,
                environment.getTunnelReadyUrl(),
                environment.getTunnelMetricsPort(),
                8
        ));

        definitions.keySet().forEach(k -> statuses.put(k, ServiceStatus.NOT_RUNNING));
    }

    /**
     * Tienda Infinito: preferir token remoto ({@code ~/.cloudflared/tienda-infinito.token}).
     * Dev/sandbox: {@code ~/.cloudflared/config.yml} del túnel pos-local.
     */
    private String tunnelStartCommand() {
        Path cfHome = Path.of(System.getProperty("user.home"), ".cloudflared");
        if (environment == LaunchEnvironment.TIENDA_INFINITO) {
            Path token = cfHome.resolve("tienda-infinito.token");
            if (Files.isRegularFile(token)) {
                String path = token.toAbsolutePath().toString();
                if (OsSupport.isWindows()) {
                    return "powershell.exe -NoProfile -Command \"cloudflared tunnel --metrics 127.0.0.1:"
                            + environment.getTunnelMetricsPort()
                            + " run --token ((Get-Content -Raw '"
                            + path.replace("'", "''") + "').Trim())\"";
                }
                return "cloudflared tunnel --metrics 127.0.0.1:" + environment.getTunnelMetricsPort()
                        + " run --token \"$(tr -d '[:space:]' < '"
                        + path.replace("'", "'\\''") + "')\"";
            }
            Path yml = cfHome.resolve("config-tienda-infinito.yml");
            return "cloudflared tunnel --metrics 127.0.0.1:" + environment.getTunnelMetricsPort()
                    + " --config \"" + yml + "\" run " + environment.getTunnelName();
        }
        Path yml = cfHome.resolve("config.yml");
        return "cloudflared tunnel --metrics 127.0.0.1:" + environment.getTunnelMetricsPort()
                + " --config \"" + yml + "\" run " + environment.getTunnelName();
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
                if (skipStart(key)) continue;
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
            if (skipStart(key) && !NAME_DB.equals(key)) {
                statuses.put(key, ServiceStatus.NOT_RUNNING);
                return;
            }
            ServiceDefinition def = definitions.get(key);
            if (def == null || def.getStartCommand() == null) return;
            if (key.equals(NAME_TUNNEL)) {
                ensureTunnelAutostart();
                return;
            }
            if (isDefinitionReachable(key, def)) {
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

                Map<String, String> extraEnv = new java.util.HashMap<>();
                extraEnv.put("PATH", mergedPath(JAVA_BIN_DIR));
                if (JAVA_BIN_DIR != null) {
                    extraEnv.put("JAVA_HOME", Path.of(JAVA_BIN_DIR).getParent().toString());
                }
                if (key.equals(NAME_FRONT)) {
                    extraEnv.put("PORT", String.valueOf(environment.getHealthPort()));
                }

                Process process = OsSupport.startLogged(
                        fullCommand,
                        def.getWorkingDir().toFile(),
                        logFilePath,
                        extraEnv
                );
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
                OsSupport.startLogged(def.getStopCommand(), def.getWorkingDir().toFile(), null, null);
            } catch (IOException e) {
                System.err.println("Error al detener contenedor Docker: " + e.getMessage());
            }
        } else if (key.equals(NAME_FRONT)) {
            System.out.println("Deteniendo Frontend en puertos " + def.getTcpPort() + " y "
                    + environment.getHealthPort());
            if (def.getTcpPort() != null) {
                OsSupport.killProcessOnPort(def.getTcpPort(), OWN_PID);
            }
            OsSupport.killProcessOnPort(environment.getHealthPort(), OWN_PID);
        } else if (key.equals(NAME_CADDY)) {
            if (def.getTcpPort() != null) {
                OsSupport.killProcessOnPort(def.getTcpPort(), OWN_PID);
            }
            OsSupport.killProcessByPattern("caddy run");
        } else if (key.equals(NAME_TUNNEL)) {
            System.out.println("El túnel Cloudflare es de inicio de sesión; no se detiene con el POS.");
            return;
        } else if (def.getTcpPort() != null && (def.getType() == ServiceType.NODE_NPM
                || def.getType() == ServiceType.JAVA_JAR
                || def.getType() == ServiceType.PROCESS)) {
            System.out.println("Deteniendo servicio " + def.getName() + " en puerto " + def.getTcpPort());
            OsSupport.killProcessOnPort(def.getTcpPort(), OWN_PID);
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
        if (CloudflaredAutostart.isReady(environment)
                || OsSupport.isProcessMatching("cloudflared")) {
            return Optional.of(environment.getPublicUrl() + MICOTIZACION_PATH);
        }
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

    public String publicAppUrl() {
        return environment.getPublicUrl();
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

    private String mergedPath(String javaBinDir) {
        String currentPath = System.getenv("PATH");
        String extra;
        if (OsSupport.isMac()) {
            extra = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin";
        } else if (OsSupport.isWindows()) {
            extra = "C:\\Windows\\System32;C:\\Windows";
        } else {
            extra = "/usr/local/bin:/usr/bin:/bin";
        }
        String combined = (currentPath != null ? currentPath + File.pathSeparator : "") + extra;
        if (javaBinDir != null) {
            combined = javaBinDir + File.pathSeparator + combined;
        }
        return combined;
    }

    private void applyShellPath(ProcessBuilder pb, String javaBinDir) {
        Map<String, String> env = pb.environment();
        env.put("PATH", mergedPath(javaBinDir));
        if (javaBinDir != null) {
            env.put("JAVA_HOME", Path.of(javaBinDir).getParent().toString());
        }
    }

    public void restart(String key) {
        startExecutor.execute(() -> {
            if (key.equals(NAME_TUNNEL)) {
                statuses.put(NAME_TUNNEL, ServiceStatus.STARTING);
                try {
                    CloudflaredAutostart.restart(environment, msg -> System.out.println("[tunnel] " + msg));
                    statuses.put(
                            NAME_TUNNEL,
                            CloudflaredAutostart.isReady(environment)
                                    || OsSupport.isProcessMatching("cloudflared")
                                    ? ServiceStatus.RUNNING
                                    : ServiceStatus.STARTING
                    );
                } catch (IOException e) {
                    statuses.put(NAME_TUNNEL, ServiceStatus.FAILED);
                    System.err.println("No se pudo reiniciar el túnel: " + e.getMessage());
                }
                return;
            }
            stop(key);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            start(key);
        });
    }

    public void startAll() {
        getServiceKeysInOrder().stream().filter(key -> !skipStart(key)).forEach(this::start);
    }

    public void stopAll() {
        new ArrayList<>(definitions.keySet()).stream().filter(key -> !skipStop(key)).forEach(this::stop);
    }

    /** Postgres lo arranca Docker a mano. Caja actual no levanta correo/túnel. */
    private boolean skipStart(String key) {
        if (NAME_DB.equals(key)) {
            return true;
        }
        if (NAME_SMTP.equals(key) && !environment.startsSmtp()) {
            return true;
        }
        if (NAME_PUENTE.equals(key) && !environment.startsPuente()) {
            return true;
        }
        if (NAME_CADDY.equals(key) && !environment.startsCaddy()) {
            return true;
        }
        if (NAME_TUNNEL.equals(key) && !environment.startsTunnel()) {
            return true;
        }
        return false;
    }

    /** El túnel sobrevive a Detener todo: es servicio de login, no hijo del POS. */
    private boolean skipStop(String key) {
        return NAME_DB.equals(key) || NAME_TUNNEL.equals(key);
    }

    private void ensureTunnelAutostart() {
        if (!environment.startsTunnel()) {
            statuses.put(NAME_TUNNEL, ServiceStatus.NOT_RUNNING);
            return;
        }
        statuses.put(NAME_TUNNEL, ServiceStatus.STARTING);
        try {
            CloudflaredAutostart.ensureInstalledAndRunning(
                    environment,
                    msg -> System.out.println("[tunnel] " + msg)
            );
            statuses.put(
                    NAME_TUNNEL,
                    CloudflaredAutostart.isReady(environment) || OsSupport.isProcessMatching("cloudflared")
                            ? ServiceStatus.RUNNING
                            : ServiceStatus.STARTING
            );
        } catch (Exception e) {
            statuses.put(NAME_TUNNEL, ServiceStatus.FAILED);
            System.err.println("No se pudo dejar el túnel Cloudflare en autostart: " + e.getMessage());
        }
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
        ProcessBuilder pb;
        if (OsSupport.isWindows()) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.directory(workingDir).redirectErrorStream(true);
        applyShellPath(pb, javaBinDir);

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
            Path mvnUnix = Path.of(mavenHome, "bin", "mvn");
            Path mvnWin = Path.of(mavenHome, "bin", "mvn.cmd");
            if (Files.exists(mvnWin)) return mvnWin.toAbsolutePath().toString();
            if (Files.exists(mvnUnix)) return mvnUnix.toAbsolutePath().toString();
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
        if (osName.contains("win")) {
            List<Path> winJvm = List.of(
                    Path.of("C:\\Program Files\\Java"),
                    Path.of("C:\\Program Files\\Microsoft"),
                    Path.of(System.getProperty("user.home"), ".jdks")
            );
            for (Path root : winJvm) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (var stream = Files.walk(root, 4)) {
                    Optional<Path> javaExe = stream
                            .filter(p -> p.getFileName().toString().equalsIgnoreCase("java.exe"))
                            .findFirst();
                    if (javaExe.isPresent()) {
                        return javaExe.get().getParent().toAbsolutePath().toString();
                    }
                } catch (IOException ignored) {}
            }
        }
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

        if (isDefinitionReachable(key, def)) return ServiceStatus.RUNNING;
        if (key.equals(NAME_TUNNEL) && (alive || OsSupport.isProcessMatching("cloudflared"))) {
            return ServiceStatus.RUNNING;
        }
        if (alive) {
            return def.getHealthUrl() != null ? ServiceStatus.STARTING : ServiceStatus.RUNNING;
        }
        if (def.getType() == ServiceType.DOCKER && def.getTcpPort() != null) {
            if (healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000)) return ServiceStatus.RUNNING;
        }
        return ServiceStatus.NOT_RUNNING;
    }

    /**
     * Front: Angular en su puerto cuenta como arriba aunque el health :3001
     * siga en 503 (pasa si se cambia la fecha del sistema).
     */
    private boolean isDefinitionReachable(String key, ServiceDefinition def) {
        if (key.equals(NAME_FRONT) && def.getTcpPort() != null
                && healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000)) {
            return true;
        }
        if (def.getHealthUrl() != null) {
            return healthChecker.isHttpHealthy(def.getHealthUrl());
        }
        if (def.getTcpPort() != null) {
            return healthChecker.isTcpOpen("localhost", def.getTcpPort(), 1000);
        }
        return false;
    }

    private void killProcessOnPort(int port) {
        OsSupport.killProcessOnPort(port, OWN_PID);
    }
}
