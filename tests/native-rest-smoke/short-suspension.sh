#!/system/bin/sh
# Run under /system/bin/sh as the existing shell user. One worker owns one temporary
# suspension. Parent may request early release by creating "stop" inside the same
# unique directory; owner still verifies release. This helper is never in the APK.
set -u
cd "${0%/*}" || exit 2
case "${1:-}" in
  --emulator-fixture)
    [ "$(getprop ro.kernel.qemu)" = 1 ] || exit 2
    pkg=dev.local.dodgelab ;;
  --authorized-iqoo-calculator)
    [ "$(getprop ro.product.model)" = V2307A ] || exit 2
    pkg=com.android.bbkcalculator ;;
  *) echo 'refused target'; exit 2 ;;
esac
duration=${2:-}
case "$duration" in ''|*[!0-9]*) exit 2 ;; esac
[ "$duration" -ge 4 ] && [ "$duration" -le 90 ] || exit 2
[ "$(id -u)" = 2000 ] && [ "$(am get-current-user)" = 0 ] || exit 2
state() {
  dumpsys package "$pkg" | awk -v p="$pkg" '
    $1=="Package" && $2=="["p"]" {found=1;next}
    found && $1=="Package" {exit}
    found && $1=="User" && $2=="0:" {
      for(i=1;i<=NF;i++)if($i~/^suspended=(true|false)$/){split($i,o,"=");print o[2];exit}
    }'
}
now() { read -r u rest < /proc/uptime; echo "${u%%.*}"; }
if [ "$(state)" != false ]; then echo 'REFUSED: already suspended or original state unknown'; exit 2; fi
[ ! -f stop ] || { echo 'not started: stop already requested'; exit 2; }
release() {
  rc=$?
  trap - EXIT TERM INT
  # Android only removes suspension attributed to this shell caller.
  if [ "$(state)" != false ]; then cmd package unsuspend --user 0 "$pkg"; fi
  final=$(state)
  if [ "$final" = false ]; then echo 'RESTORED suspended=false'; exit "$rc"; fi
  echo "RESTORE UNCONFIRMED: suspended=${final:-unknown}; recheck owner user 0"; exit 1
}
deadline=$(( $(now) + duration ))
trap release EXIT
trap 'exit 0' TERM INT
echo "ARMED user=0 pkg=$pkg seconds=$duration"
cmd package suspend --user 0 "$pkg" || { error=$?; echo "SUSPEND COMMAND FAILED rc=$error"; exit "$error"; }
if [ "$(state)" != true ]; then echo 'SUSPEND NOT CONFIRMED'; exit 1; fi
echo 'ACTIVE suspended=true'
while [ ! -f stop ] && [ "$(now)" -lt "$deadline" ]; do sleep 1; done
