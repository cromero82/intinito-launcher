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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Caddy del POS: binario propio del launcher en {@code ~/.infinitesoft/bin},
 * no depende del PATH de Windows (que se pierde al reiniciar o no llega a {@code cmd /c}).
 */
final class CaddySupport {
    private static final String CADDY_VERSION = "2.10.2";
    private static final Path MANAGED_DIR =
            Path.of(System.getProperty("user.home"), ".infinitesoft", "bin");

    private CaddySupport() {}

    static Path ensureBinary() throws IOException {
        Path existing = resolveBinary();
        if (existing != null) {
            return existing;
        }
        if (!OsSupport.isWindows()) {
            throw new IOException("No está caddy en PATH ni en las rutas habituales (brew install caddy).");
        }
        Files.createDirectories(MANAGED_DIR);
        Path dest = managedBinary();
        downloadWindowsZip(dest);
        if (!Files.isRegularFile(dest)) {
            throw new IOException("No se pudo instalar Caddy en " + dest);
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

    static List<String> runCommand(Path binary, String caddyfileName) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toAbsolutePath().toString());
        cmd.add("run");
        cmd.add("--config");
        cmd.add(caddyfileName);
        return cmd;
    }

    private static Path managedBinary() {
        String name = OsSupport.isWindows() ? "caddy.exe" : "caddy";
        return MANAGED_DIR.resolve(name);
    }

    private static List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        list.add(managedBinary());
        String home = System.getProperty("user.home");
        list.add(Path.of("C:\\dev\\tools\\caddy\\caddy.exe"));
        list.add(Path.of("C:\\Program Files\\Caddy\\caddy.exe"));
        list.add(Path.of("C:\\ProgramData\\chocolatey\\bin\\caddy.exe"));
        list.add(Path.of(home, "scoop", "apps", "caddy", "current", "caddy.exe"));
        list.add(Path.of("/opt/homebrew/bin/caddy"));
        list.add(Path.of("/usr/local/bin/caddy"));
        list.add(Path.of("/usr/bin/caddy"));
        String which = firstOnPath();
        if (which != null) {
            list.add(Path.of(which));
        }
        return list;
    }

    private static String firstOnPath() {
        List<String> probe = OsSupport.isWindows()
                ? List.of("cmd.exe", "/c", "where caddy")
                : List.of("sh", "-c", "command -v caddy");
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

    private static void downloadWindowsZip(Path destExe) throws IOException {
        String url = "https://github.com/caddyserver/caddy/releases/download/v"
                + CADDY_VERSION + "/caddy_" + CADDY_VERSION + "_windows_amd64.zip";
        Path zip = Files.createTempFile("caddy-", ".zip");
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(zip);
            throw new IOException("No se pudo descargar Caddy desde " + url + ": " + e.getMessage(), e);
        }
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            boolean found = false;
            while ((entry = zis.getNextEntry()) != null) {
                if ("caddy.exe".equalsIgnoreCase(Path.of(entry.getName()).getFileName().toString())) {
                    Files.copy(zis, destExe, StandardCopyOption.REPLACE_EXISTING);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("El zip de Caddy no trae caddy.exe");
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }
}
