#!/bin/bash

# Infinito Launcher - macOS Run Script
# This script runs the Infinito Launcher on macOS

echo "Starting Infinito Launcher on macOS..."

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not in PATH."
    echo "Please install Java 11 or higher from https://adoptium.net/"
    exit 1
fi

# Check if Maven is installed (for build operations)
if ! command -v mvn &> /dev/null; then
    echo "Warning: Maven is not installed or not in PATH."
    echo "Some features (project updates) may not work correctly."
    echo "Install Maven from https://maven.apache.org/download.cgi"
fi

# Check if Docker is installed (for PostgreSQL)
if ! command -v docker &> /dev/null; then
    echo "Warning: Docker is not installed or not in PATH."
    echo "Database service will not work."
    echo "Install Docker Desktop from https://www.docker.com/products/docker-desktop"
fi

# Check if npm is installed (for frontend)
if ! command -v npm &> /dev/null; then
    echo "Warning: npm is not installed or not in PATH."
    echo "Frontend service will not work."
    echo "Install Node.js from https://nodejs.org/"
fi

# Build the project if target doesn't exist
if [ ! -f "target/infinito-launcher.jar" ]; then
    echo "Building project..."
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        echo "Error: Build failed."
        exit 1
    fi
fi

# Run the launcher
echo "Launching Infinito Launcher..."
java -jar target/infinito-launcher.jar
