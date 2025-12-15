package com.infinitesoft.launcher;

import com.infinitesoft.launcher.core.ServiceManager;
import com.infinitesoft.launcher.core.ServiceStatus;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HelloController {
    private final ServiceManager serviceManager = new ServiceManager();
    private final ScheduledExecutorService uiRefresher = Executors.newSingleThreadScheduledExecutor();

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

    private void refreshUI() {
        Map<String, ServiceStatus> map = serviceManager.snapshotStatuses();
        Platform.runLater(() -> {
            setStatusLabel(statusDb, map.getOrDefault(ServiceManager.NAME_DB, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSecurity, map.getOrDefault(ServiceManager.NAME_SECURITY, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSmtp, map.getOrDefault(ServiceManager.NAME_SMTP, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusStore, map.getOrDefault(ServiceManager.NAME_STORE, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusFront, map.getOrDefault(ServiceManager.NAME_FRONT, ServiceStatus.NOT_RUNNING));

            labelGeneralStatus.setText(computeGeneralStatus(map));
        });
    }

    private void setStatusLabel(Label label, ServiceStatus status) {
        String text;
        switch (status) {
            case RUNNING: text = "Ejecutándose"; break;
            case STARTING: text = "Iniciando"; break;
            case FAILED: text = "Falló"; break;
            default: text = "Detenido"; break;
        }
        label.setText(text);
        // Colorear fondo según estado (solo verde para Ejecutándose y rojo para Detenido)
        switch (status) {
            case RUNNING:
                label.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white;");
                break;
            case NOT_RUNNING:
                label.setStyle("-fx-background-color: #c62828; -fx-text-fill: white;");
                break;
            default:
                // limpiar estilo para otros estados (Iniciando, Falló) o mantener por defecto
                label.setStyle("");
        }
    }

    private String computeGeneralStatus(Map<String, ServiceStatus> map) {
        boolean anyFailed = map.values().stream().anyMatch(s -> s == ServiceStatus.FAILED);
        if (anyFailed) return "Ejecución parcial";
        boolean anyStarting = map.values().stream().anyMatch(s -> s == ServiceStatus.STARTING);
        if (anyStarting) return "Iniciando";
        boolean allRunning = map.values().stream().allMatch(s -> s == ServiceStatus.RUNNING);
        if (allRunning) return "Ejecutándose";
        // Alguno detenido: ejecución parcial según requerimiento
        return "Ejecución parcial";
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

    // DB
    @FXML protected void onStartDb() { serviceManager.start(ServiceManager.NAME_DB); }
    @FXML protected void onStopDb() { serviceManager.stop(ServiceManager.NAME_DB); }
    @FXML protected void onRestartDb() { serviceManager.restart(ServiceManager.NAME_DB); }

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
