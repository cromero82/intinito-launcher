package com.infinitesoft.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * IP LAN del servidor POS. Se persiste en un archivo de texto en runtime
 * (~/.infinitesoft/host-ip.txt), inicializado desde el recurso empaquetado
 * com/infinitesoft/launcher/host-ip.txt.
 */
public class HostConfig {
    public static final String DEFAULT_LAN_HOST = "192.168.1.6";
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final String RESOURCE_PATH = "/com/infinitesoft/launcher/host-ip.txt";
    private static final Path RUNTIME_HOST_FILE =
            Path.of(System.getProperty("user.home"), ".infinitesoft", "host-ip.txt");

    private static HostConfig instance;

    private HostConfig() {
        ensureRuntimeFile();
    }

    public static HostConfig getInstance() {
        if (instance == null) {
            instance = new HostConfig();
        }
        return instance;
    }

    public synchronized String getLanHost() {
        ensureRuntimeFile();
        try {
            return parseHost(Files.readString(RUNTIME_HOST_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return DEFAULT_LAN_HOST;
        }
    }

    public synchronized boolean setLanHost(String host) throws IOException {
        String normalized = validateAndNormalize(host);
        String previous = getLanHost();
        ensureRuntimeFile();
        writeHostFile(RUNTIME_HOST_FILE, normalized);
        return !normalized.equals(previous);
    }

    public Path getRuntimeHostFile() {
        return RUNTIME_HOST_FILE;
    }

    public String getTicketsUrl() {
        return "http://" + getLanHost() + ":4200/apps/tickets";
    }

    public String getLoginUrl() {
        return "http://" + getLanHost() + ":4200/login";
    }

    public void syncProjectMirror(String projectsBasePath) throws IOException {
        if (projectsBasePath == null || projectsBasePath.isBlank()) {
            return;
        }
        Path mirrorDir = Path.of(projectsBasePath, "infinito-ai-front", ".launcher");
        Files.createDirectories(mirrorDir);
        writeHostFile(mirrorDir.resolve("host-ip.txt"), getLanHost());
    }

    public boolean requiresTunnelRedeploy(String host) {
        return !DEFAULT_LAN_HOST.equals(validateAndNormalize(host));
    }

    private void ensureRuntimeFile() {
        try {
            Files.createDirectories(RUNTIME_HOST_FILE.getParent());
            if (!Files.exists(RUNTIME_HOST_FILE)) {
                try (InputStream in = HostConfig.class.getResourceAsStream(RESOURCE_PATH)) {
                    if (in != null) {
                        Files.copy(in, RUNTIME_HOST_FILE);
                    } else {
                        writeHostFile(RUNTIME_HOST_FILE, DEFAULT_LAN_HOST);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo inicializar host-ip.txt", e);
        }
    }

    private void writeHostFile(Path target, String host) throws IOException {
        String content = "# IP LAN del servidor POS (modificable desde el lanzador)\n" + host + "\n";
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private String parseHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LAN_HOST;
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            return validateAndNormalize(trimmed);
        }
        return DEFAULT_LAN_HOST;
    }

    private String validateAndNormalize(String host) {
        if (host == null) {
            throw new IllegalArgumentException("La IP no puede estar vacía.");
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("La IP no puede estar vacía.");
        }
        var matcher = IPV4_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Formato de IP inválido. Use IPv4, p.ej. 192.168.1.6");
        }
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Cada octeto debe estar entre 0 y 255.");
            }
        }
        return trimmed;
    }
}
