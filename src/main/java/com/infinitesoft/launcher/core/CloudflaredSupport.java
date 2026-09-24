package com.infinitesoft.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * cloudflared del POS: binario propio en {@code ~/.infinitesoft/bin},
 * igual que Caddy — no depende del PATH de Windows.
 */
final class CloudflaredSupport {
    private static final String CLOUDFLARED_VERSION = "2025.8.1";
    private static final Path MANAGED_DIR =
            Path.of(System.getProperty("user.home"), ".infinitesoft", "bin");

    private CloudflaredSupport() {}

    static Path ensureBinary() throws IOException {
        Path existing = resolveBinary();
        if (existing != null) {
            return existing;
        }
        if (!OsSupport.isWindows()) {
            throw new IOException("No está cloudflared en PATH ni en las rutas habituales (brew install cloudflared).");
        }
        Files.createDirectories(MANAGED_DIR);
        Path dest = managedBinary();
        downloadWindowsExe(dest);
        if (!Files.isRegularFile(dest)) {
            throw new IOException("No se pudo instalar cloudflared en " + dest);
        }
        return dest;
    }

    static Path resolveBinary() {
        for (Path p : candidates()) {
            if (isUsable(p)) {
                return p.toAbsolutePath();
            }
        }
        return null;
    }

    private static Path managedBinary() {
        String name = OsSupport.isWindows() ? "cloudflared.exe" : "cloudflared";
        return MANAGED_DIR.resolve(name);
    }

    private static List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        list.add(managedBinary());
        String home = System.getProperty("user.home");
        list.add(Path.of("C:\\Program Files\\cloudflared\\cloudflared.exe"));
        list.add(Path.of("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe"));
        list.add(Path.of("C:\\ProgramData\\chocolatey\\bin\\cloudflared.exe"));
        list.add(Path.of(home, "scoop", "apps", "cloudflared", "current", "cloudflared.exe"));
        list.add(Path.of(home, "bin", "cloudflared.exe"));
        list.add(Path.of(home, "bin", "cloudflared"));
        list.add(Path.of("/opt/homebrew/bin/cloudflared"));
        list.add(Path.of("/usr/local/bin/cloudflared"));
        list.add(Path.of("/usr/bin/cloudflared"));
        String which = firstOnPath();
        if (which != null) {
            list.add(Path.of(which));
        }
        return list;
    }

    private static String firstOnPath() {
        List<String> probe = OsSupport.isWindows()
                ? List.of("cmd.exe", "/c", "where cloudflared")
                : List.of("sh", "-c", "command -v cloudflared");
        try {
            Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            String line;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                line = reader.readLine();
            }
            p.waitFor();
            if (line == null || line.isBlank()) {
                return null;
            }
            String first = line.trim().split("\\r?\\n")[0].trim();
            String lower = first.toLowerCase(Locale.ROOT);
            if (lower.contains("no se pudo") || lower.contains("not found") || lower.startsWith("info:")) {
                return null;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsable(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return false;
        }
        if (OsSupport.isWindows()) {
            return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
        }
        return Files.isExecutable(p);
    }

    private static void downloadWindowsExe(Path destExe) throws IOException {
        String url = "https://github.com/cloudflare/cloudflared/releases/download/"
                + CLOUDFLARED_VERSION + "/cloudflared-windows-amd64.exe";
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, destExe, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(destExe);
            throw new IOException("No se pudo descargar cloudflared desde " + url + ": " + e.getMessage(), e);
        }
    }
}
