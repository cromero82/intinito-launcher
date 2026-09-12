package com.infinitesoft.launcher.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arranque, parada y firewall según Windows / macOS / Linux.
 * El launcher no está atado a un solo sistema operativo.
 */
public final class OsSupport {
    private OsSupport() {}

    public static boolean isWindows() {
        return osName().contains("win");
    }

    public static boolean isMac() {
        return osName().contains("mac");
    }

    public static boolean isLinux() {
        String n = osName();
        return n.contains("nux") || n.contains("nix");
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    public static Process startLogged(String command, File workDir, Path logFile, Map<String, String> extraEnv)
            throws IOException {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        if (workDir != null) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(true);
        if (logFile != null) {
            File log = logFile.toFile();
            File parent = log.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            pb.redirectOutput(log);
        }
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        return pb.start();
    }

    public static void killProcessOnPort(int port, long ownPid) {
        if (isWindows()) {
            killPortWindows(port, ownPid);
        } else {
            killPortUnix(port, ownPid);
        }
    }

    public static void killProcessByPattern(String pattern) {
        try {
            if (isWindows()) {
                String safe = pattern.replace("'", "").replace("\"", "");
                new ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-Command",
                        "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*"
                                + safe
                                + "*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
                ).start();
            } else {
                new ProcessBuilder("sh", "-c", "pkill -f '" + pattern.replace("'", "'\\''") + "'").start();
            }
        } catch (IOException e) {
            System.err.println("No se pudo detener el patrón " + pattern + ": " + e.getMessage());
        }
    }

    public static boolean isProcessMatching(String pattern) {
        try {
            Process p;
            if (isWindows()) {
                String safe = pattern.replace("'", "").replace("\"", "").replace("`", "");
                p = new ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-Command",
                        "$p = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*"
                                + safe
                                + "*' } | Select-Object -First 1; if ($p) { $p.ProcessId }"
                ).start();
            } else {
                p = new ProcessBuilder("sh", "-c", "pgrep -f '" + pattern.replace("'", "'\\''") + "'").start();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                return line != null && !line.isBlank();
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static String pathSeparatorHint() {
        return isWindows() ? ";" : ":";
    }

    public static List<Integer> lanListenPorts(LaunchEnvironment env) {
        List<Integer> ports = new ArrayList<>();
        ports.add(env.getFrontPort());
        ports.add(env.getCaddyPort());
        ports.add(env.getAuthPort());
        ports.add(env.getSmtpPort());
        ports.add(env.getStorePort());
        ports.add(env.getPuentePort());
        ports.add(env.getHealthPort());
        ports.add(5432);
        return ports;
    }

    private static void killPortUnix(int port, long ownPid) {
        try {
            Process finder = new ProcessBuilder("sh", "-c", "lsof -tiTCP:" + port + " -sTCP:LISTEN").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(finder.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    killPidIfForeign(line.trim(), ownPid, port);
                }
            }
            finder.waitFor();
        } catch (Exception e) {
            System.err.println("Error al liberar puerto " + port + ": " + e.getMessage());
        }
    }

    private static void killPortWindows(int port, long ownPid) {
        try {
            Process finder = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "netstat -ano | findstr :" + port
            ).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(finder.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.toUpperCase(Locale.ROOT).contains("LISTENING")) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 0) {
                        continue;
                    }
                    killPidIfForeign(parts[parts.length - 1], ownPid, port);
                }
            }
            finder.waitFor();
        } catch (Exception e) {
            System.err.println("Error al liberar puerto " + port + ": " + e.getMessage());
        }
    }

    private static void killPidIfForeign(String pidText, long ownPid, int port) {
        if (pidText == null || pidText.isBlank()) {
            return;
        }
        try {
            long targetPid = Long.parseLong(pidText);
            if (targetPid == ownPid) {
                System.err.println("No se mata al launcher (PID " + targetPid + ") en puerto " + port);
                return;
            }
            if (isWindows()) {
                new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(targetPid)).start();
            } else {
                new ProcessBuilder("kill", "-9", String.valueOf(targetPid)).start();
            }
        } catch (NumberFormatException | IOException ignored) {
            // PID ilegible o proceso ya cerrado
        }
    }
}
