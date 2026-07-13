#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
USB_SERIAL="${ANDROID_TV_USB_SERIAL:-14291HFDD2RTE3}"
TCP_PORT="${ANDROID_TV_ADB_PORT:-5555}"
CONNECT_TIMEOUT="${ANDROID_TV_CONNECT_TIMEOUT:-180}"
WATCH_INTERVAL="${ANDROID_TV_WATCH_INTERVAL:-10}"
MODE="${1:---once}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

device_state() {
  "$ADB" -s "$1" get-state 2>/dev/null | tr -d '\r' || true
}

is_device() {
  [[ "$(device_state "$1")" == "device" ]]
}

tcp_endpoint_for_ip() {
  local ip="$1"
  printf '%s:%s' "$ip" "$TCP_PORT"
}

current_usb_ip() {
  local route ip
  route="$("$ADB" -s "$USB_SERIAL" shell ip route get 8.8.8.8 2>/dev/null | tr -d '\r' || true)"
  ip="$(printf '%s\n' "$route" | sed -n 's/.* src \([0-9.][0-9.]*\).*/\1/p' | head -n 1)"

  if [[ -n "$ip" ]]; then
    printf '%s\n' "$ip"
    return 0
  fi

  "$ADB" -s "$USB_SERIAL" shell ip -o -4 addr show wlan0 2>/dev/null \
    | tr -d '\r' \
    | sed -n 's/.* inet \([0-9.][0-9.]*\)\/.*/\1/p' \
    | head -n 1
}

wait_for_usb() {
  local start now
  start="$(date +%s)"

  while true; do
    if is_device "$USB_SERIAL"; then
      return 0
    fi

    now="$(date +%s)"
    if (( now - start >= CONNECT_TIMEOUT )); then
      log "Timed out waiting for USB ADB serial $USB_SERIAL"
      return 1
    fi

    sleep 2
  done
}

find_live_tcp_endpoint() {
  local endpoint state

  while read -r endpoint state _; do
    if [[ "$endpoint" == *":$TCP_PORT" && "$state" == "device" ]]; then
      if "$ADB" -s "$endpoint" shell true >/dev/null 2>&1; then
        printf '%s\n' "$endpoint"
        return 0
      fi
    fi
  done < <("$ADB" devices -l | sed '1d')

  return 1
}

connect_once() {
  local existing service_port ip endpoint

  existing="$(find_live_tcp_endpoint || true)"
  if [[ -n "$existing" ]]; then
    log "TCP ADB already connected at $existing"
    return 0
  fi

  log "Waiting for USB ADB serial $USB_SERIAL"
  wait_for_usb

  service_port="$("$ADB" -s "$USB_SERIAL" shell getprop service.adb.tcp.port 2>/dev/null | tr -d '\r' || true)"
  if [[ "$service_port" != "$TCP_PORT" ]]; then
    log "Enabling TCP ADB on port $TCP_PORT"
    "$ADB" -s "$USB_SERIAL" tcpip "$TCP_PORT" >/dev/null
    sleep 3
    wait_for_usb || true
  fi

  ip="$(current_usb_ip)"
  if [[ -z "$ip" ]]; then
    log "Could not determine Android TV IP from USB ADB"
    return 1
  fi

  endpoint="$(tcp_endpoint_for_ip "$ip")"
  log "Connecting to $endpoint"
  "$ADB" disconnect "$endpoint" >/dev/null 2>&1 || true
  "$ADB" connect "$endpoint" >/dev/null

  if is_device "$endpoint"; then
    "$ADB" -s "$endpoint" shell true >/dev/null
    log "Connected to Android TV over TCP at $endpoint"
    return 0
  fi

  log "TCP endpoint $endpoint did not authorize as a device"
  return 1
}

case "$MODE" in
  --once)
    connect_once
    ;;
  --watch)
    log "Starting Android TV ADB reconnect watcher for $USB_SERIAL on port $TCP_PORT"
    while true; do
      connect_once || true
      sleep "$WATCH_INTERVAL"
    done
    ;;
  *)
    printf 'Usage: %s [--once|--watch]\n' "$0" >&2
    exit 2
    ;;
esac
