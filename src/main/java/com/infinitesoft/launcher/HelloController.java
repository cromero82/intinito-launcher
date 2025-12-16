package com.infinitesoft.launcher;

import com.infinitesoft.launcher.core.ServiceManager;
import com.infinitesoft.launcher.core.ServiceStatus;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HelloController {
    private final ServiceManager serviceManager = new ServiceManager();
    private final ScheduledExecutorService uiRefresher = Executors.newSingleThreadScheduledExecutor();

    @FXML private Button launchAppButton;
    @FXML private Label labelGeneralStatus;
    @FXML private Label statusDb;
    @FXML private Label statusSecurity;
    @FXML private Label statusSmtp;
    @FXML private Label statusStore;
    @FXML private Label statusFront;

    @FXML
    public void initialize() {
        // Programar refresco periódico de estados
        uiRefresher.scheduleAtFixedRate(this::refreshUI, 0, 1500, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        serviceManager.shutdown();
        uiRefresher.shutdownNow();
    }

    private void refreshUI() {
        Map<String, ServiceStatus> map = serviceManager.snapshotStatuses();
        Platform.runLater(() -> {
            setStatusLabel(statusDb, map.getOrDefault(ServiceManager.NAME_DB, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSecurity, map.getOrDefault(ServiceManager.NAME_SECURITY, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSmtp, map.getOrDefault(ServiceManager.NAME_SMTP, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusStore, map.getOrDefault(ServiceManager.NAME_STORE, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusFront, map.getOrDefault(ServiceManager.NAME_FRONT, ServiceStatus.NOT_RUNNING));

            computeGeneralStatus(map);

            boolean allRequiredRunning = map.get(ServiceManager.NAME_FRONT) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_STORE) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_SECURITY) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_DB) == ServiceStatus.RUNNING;
            launchAppButton.setDisable(!allRequiredRunning);
        });
    }

    private void setStatusLabel(Label label, ServiceStatus status) {
        String text;
        String style;
        switch (status) {
            case RUNNING:
                text = "Ejecutándose";
                style = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
                break;
            case STARTING:
                text = "Iniciando";
                style = "-fx-background-color: #f9a825; -fx-text-fill: white;";
                break;
            case FAILED:
                text = "Falló";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
            default:
                text = "Detenido";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
        }
        label.setText(text);
        label.setStyle(style);
    }

    private void computeGeneralStatus(Map<String, ServiceStatus> map) {
        boolean anyFailed = map.values().stream().anyMatch(s -> s == ServiceStatus.FAILED);
        boolean anyStarting = map.values().stream().anyMatch(s -> s == ServiceStatus.STARTING);
        boolean allRunning = map.values().stream().allMatch(s -> s == ServiceStatus.RUNNING);
        boolean allStopped = map.values().stream().allMatch(s -> s == ServiceStatus.NOT_RUNNING);

        String statusText;
        String statusStyle;

        if (allRunning) {
            statusText = "Ejecutándose";
            statusStyle = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
        } else if (allStopped) {
            statusText = "Detenido";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        } else if (anyStarting) {
            statusText = "Iniciando";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        } else {
            statusText = "Ejecución parcial";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        }

        if (anyFailed) {
            statusText = "Ejecución parcial con fallos";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        }

        labelGeneralStatus.setText(statusText);
        labelGeneralStatus.setStyle(statusStyle);
    }

    @FXML
    protected void onLaunchApp() {
        final String url = "http://localhost:4200/login";
        try {
            // Intenta con Chrome
            new ProcessBuilder("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "--app=" + url).start();
        } catch (IOException e1) {
            try {
                // Si falla, intenta con Edge
                new ProcessBuilder("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe", "--app=" + url).start();
            } catch (IOException e2) {
                // Si ambos fallan, abre en el navegador por defecto
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try {
                        Desktop.getDesktop().browse(new URI(url));
                    } catch (Exception e3) {
                        // Error al abrir el navegador por defecto
                        e3.printStackTrace();
                    }
                }
            }
        }
    }

    @FXML
    protected void onOpenBrowser() {
        final String url = "http://localhost:4200/login";
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @FXML
    protected void onOpenLogs() {
        try {
            Desktop.getDesktop().open(new File("C:/dev/repos/intinito-launcher/logs"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    protected void onOpenDbManager() {
        try {
            new ProcessBuilder("C:\\Program Files\\DBeaver\\dbeaver.exe").start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Botones globales
    @FXML
    protected void onStartAll() {
        serviceManager.startAllSequential(Duration.ofSeconds(60));
    }

    @FXML
    protected void onStopAll() {
        serviceManager.stopAll();
    }

    @FXML
    protected void onRestartAll() {
        serviceManager.stopAll();
        // breve espera y secuencial
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            serviceManager.startAllSequential(Duration.ofSeconds(60));
        }).start();
    }

    // Security
    @FXML protected void onStartSecurity() { serviceManager.start(ServiceManager.NAME_SECURITY); }
    @FXML protected void onStopSecurity() { serviceManager.stop(ServiceManager.NAME_SECURITY); }
    @FXML protected void onRestartSecurity() { serviceManager.restart(ServiceManager.NAME_SECURITY); }

    // SMTP
    @FXML protected void onStartSmtp() { serviceManager.start(ServiceManager.NAME_SMTP); }
    @FXML protected void onStopSmtp() { serviceManager.stop(ServiceManager.NAME_SMTP); }
    @FXML protected void onRestartSmtp() { serviceManager.restart(ServiceManager.NAME_SMTP); }

    // Store
    @FXML protected void onStartStore() { serviceManager.start(ServiceManager.NAME_STORE); }
    @FXML protected void onStopStore() { serviceManager.stop(ServiceManager.NAME_STORE); }
    @FXML protected void onRestartStore() { serviceManager.restart(ServiceManager.NAME_STORE); }

    // Front
    @FXML protected void onStartFront() { serviceManager.start(ServiceManager.NAME_FRONT); }
    @FXML protected void onStopFront() { serviceManager.stop(ServiceManager.NAME_FRONT); }
    @FXML protected void onRestartFront() { serviceManager.restart(ServiceManager.NAME_FRONT); }
}
