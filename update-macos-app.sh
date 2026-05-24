#!/bin/bash

echo "Actualizando InfinitoLauncher.app en el escritorio..."

PROJECT_DIR="/Users/carlosromero/Documents/dev/repos/intinito-launcher"
APP_DIR="/Users/carlosromero/Desktop/InfinitoLauncher.app"

cd "$PROJECT_DIR" && mvn clean package -DskipTests

if [ $? -eq 0 ]; then
    cp "$PROJECT_DIR/target/infinito-launcher.jar" "$APP_DIR/Contents/Java/"
    echo "App actualizada correctamente."
else
    echo "Error al construir el proyecto."
    exit 1
fi
