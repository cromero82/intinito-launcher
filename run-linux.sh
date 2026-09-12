#!/bin/sh
# Infinito Launcher — Linux (también sirve en macOS si java/mvn están en PATH)
set -eu
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "Java no está en PATH. Instala JDK 11+ y vuelve a intentar." >&2
  exit 1
fi

if [ ! -f target/infinito-launcher.jar ]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "No hay JAR y Maven no está en PATH." >&2
    exit 1
  fi
  echo "Compilando launcher..."
  mvn -DskipTests package
fi

echo "Arrancando Infinito Launcher..."
exec java -jar target/infinito-launcher.jar
