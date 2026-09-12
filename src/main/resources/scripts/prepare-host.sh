#!/bin/sh
# Prepara esta máquina para el POS Infinito (macOS o Linux).
# Uso: prepare-host.sh <ambiente> [puerto ...]
# El túnel Cloudflare sale hacia internet: no hace falta abrir esos puertos
# al mundo. Las reglas de abajo sirven para cajas/tablets en la LAN.

set -eu
ENV_ID="${1:-dev-local}"
shift || true

echo "Sistema: $(uname -s)  Ambiente: $ENV_ID"
echo "Hostname público esperado:"
case "$ENV_ID" in
  sandbox) echo "  https://pos-sandbox.mayaksoluciones.com" ;;
  tienda-infinito) echo "  https://tienda-infinito.mayaksoluciones.com  (Caddy :8280, BD controlneg_rmx_db_v02; no pisa :4200/:8088)" ;;
  *) echo "  https://cotiza.mayaksoluciones.com  y  http://localhost:4200" ;;
esac

need() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "OK  $1 → $(command -v "$1")"
  else
    echo "FALTA  $1  (instálalo antes de arrancar ese servicio desde el launcher)"
  fi
}

echo
echo "Comandos en PATH:"
need java
need mvn
need docker
need npm
need caddy
need cloudflared

echo
echo "Puertos LAN a permitir (si hay firewall): $*"

os="$(uname -s)"
if [ "$os" = "Linux" ] && command -v ufw >/dev/null 2>&1; then
  if [ "$(id -u)" -ne 0 ]; then
    echo "Para escribir reglas ufw ejecuta: sudo sh $0 $ENV_ID $*"
    exit 0
  fi
  for p in "$@"; do
    ufw allow "${p}/tcp" comment "Infinito POS $ENV_ID" || true
  done
  echo "Reglas ufw aplicadas."
elif [ "$os" = "Linux" ] && command -v firewall-cmd >/dev/null 2>&1; then
  if [ "$(id -u)" -ne 0 ]; then
    echo "Para firewalld ejecuta: sudo sh $0 $ENV_ID $*"
    exit 0
  fi
  for p in "$@"; do
    firewall-cmd --permanent --add-port="${p}/tcp" || true
  done
  firewall-cmd --reload || true
  echo "Reglas firewalld aplicadas."
elif [ "$os" = "Darwin" ]; then
  echo "En macOS el firewall es por aplicación, no por puerto."
  echo "Si Ajustes → Red → Firewall está activo, permite java, node, caddy y cloudflared cuando el sistema lo pida."
  echo "Para acceso desde otra tablet en la LAN, usa la IP de esta Mac (botón Configurar IP del launcher)."
else
  echo "No se detectó ufw/firewalld. Si usas otro firewall, abre TCP: $*"
fi

echo
if [ "$ENV_ID" = "tienda-infinito" ]; then
  TOKEN_FILE="${HOME}/.cloudflared/tienda-infinito.token"
  if [ -f "$TOKEN_FILE" ]; then
    echo "OK  token del túnel Tienda Infinito: $TOKEN_FILE"
  else
    echo "FALTA  $TOKEN_FILE  (cópialo desde la laptop de desarrollo; sin él el túnel de producción no conecta)"
  fi
fi

echo
echo "El launcher instala cloudflared como inicio de sesión (LaunchAgent / systemd user / tarea ONLOGON)."
echo "Listo. Arranca los servicios desde Infinito Launcher (uno a uno o Iniciar todo)."
