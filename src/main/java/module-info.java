module com.infinitesoft.launcher {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires java.desktop;

    requires com.dlsc.formsfx;

    opens com.infinitesoft.launcher to javafx.fxml;
    exports com.infinitesoft.launcher;
}