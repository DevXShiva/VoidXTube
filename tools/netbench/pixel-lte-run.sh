#!/bin/bash
# Runs a netbench sweep over the Pixel's LTE: proxy on the phone (shell user, loopback only),
# Wi-Fi off for the run, restored at the end. Follows the Pixel hard rules: the Wi-Fi toggle
# needs guard.sh start (NewTube or launcher focused, no shade, awake, no call).
#
# Usage: NETBENCH_SERIAL=<adb serial> [NETBENCH_DATA=<dir>] pixel-lte-run.sh <run-id> <netbench run args...>
# Needs proxy/netbench-proxy-arm64 (build it from proxy/main.go). Logs and results go to
# $NETBENCH_DATA/harness/results (default: harness/results next to this script).
set -u
S=${NETBENCH_SERIAL:?set NETBENCH_SERIAL to the adb serial of the phone}
export NETBENCH_SERIAL
HERE=$(cd "$(dirname "$0")" && pwd)
GUARD=$HERE/device/guard.sh
RESULTS=${NETBENCH_DATA:-$HERE}/harness/results
mkdir -p "$RESULTS" || exit 1
RUN_ID=$1; shift
LOG=$RESULTS/$RUN_ID.pixel.log
HOME_IP=$(curl -s --max-time 10 https://api.ipify.org)
say() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
adbs() { adb -s $S "$@"; }

wifi_on() { adbs shell cmd wifi status 2>/dev/null | grep -q "Wifi is enabled"; }

# The phone-side safety timers: `sh -c "sleep 14400; ...; svc wifi enable"`, found by parsing ps on
# this host (a pattern run on the phone matches its own command line). kill_timers kills their sh
# with -9 (a TERM waits behind the sleep: two timers outlived their wrappers, 2026-09-29) and checks.
timer_pids() {
  adbs shell ps -A -o PID,ARGS 2>/dev/null | tr -d '\r' | awk '$2=="sh" && $3=="-c" && $4=="sleep" && $5=="14400;" {print $1}'
}
kill_timers() {  # label
  local p left
  p=$(timer_pids | tr '\n' ' ')
  [ -n "${p// /}" ] && adbs shell "kill -9 $p" 2>/dev/null
  adbs shell rm -f /data/local/tmp/netbench-timer.pid
  left=$(timer_pids | tr '\n' ' ')
  say "$1: phone-side timers killed [${p:-none}], left [${left:-none}]"
  [ -z "${left// /}" ]
}

restore() {
  say "restore: stopping harness (if running)"
  [ -n "${HPID:-}" ] && kill "$HPID" 2>/dev/null
  if ! wifi_on; then
    # Full guard first; after 5 min of failures fall back to "no call active" only: leaving
    # the owner without Wi-Fi is worse than a toggle while his phone sits locked.
    for i in $(seq 1 10); do
      if env -u GUARD_ALLOW_CALL "$GUARD" start >>"$LOG" 2>&1; then break; fi
      if [ "$i" -eq 10 ]; then
        # "No call" needs a READABLE call state: an unplugged phone reads empty, and that is not "no
        # call" (2026-09-29 17:32: the owner unplugged it mid-call; this loop took the empty read for
        # "no call" and logged a Wi-Fi enable that never reached the phone). Wait for the phone, up to
        # 4.5 h (its own timer re-enables Wi-Fi at 4 h).
        waited=0
        until st=$(adbs shell dumpsys telephony.registry 2>/dev/null | grep -a -o "mCallState=[0-9]" | sort -u | tr '\n' ' ') \
              && [ -n "$st" ] && ! echo "$st" | grep -q "mCallState=[12]"; do
          if [ $waited -ge 16200 ]; then say "restore: phone unreachable or in a call for 4.5 h"; break; fi
          sleep 15; waited=$((waited + 15))
        done
        say "restore: guard kept failing for 5 min; call state [${st:-unreadable}]: re-enabling Wi-Fi"
      fi
      sleep 30
    done
    for i in 1 2 3; do adbs shell svc wifi enable 2>/dev/null; sleep 3; wifi_on && break; done
    if wifi_on; then say "restore: Wi-Fi enabled (checked)"
    else say "restore: Wi-Fi NOT confirmed on (phone unreachable?): the phone-side timer stays armed"; fi
  fi
  if wifi_on; then kill_timers "restore"; else say "restore: Wi-Fi not confirmed on: the phone-side timer stays armed"; fi
  adbs shell 'kill $(pidof netbench-proxy) 2>/dev/null; rm -f /data/local/tmp/netbench-proxy'
  adbs pull /data/local/tmp/netbench-proxy.log "$RESULTS/$RUN_ID.proxy.log" >/dev/null 2>&1
  adbs shell rm -f /data/local/tmp/netbench-proxy.log
  adbs forward --remove tcp:18080 2>/dev/null
  sleep 5
  adbs shell cmd wifi status | head -1 | tee -a "$LOG"
  say "restore: done"
}
trap restore EXIT

say "setup: pushing proxy"
adbs push "$HERE/proxy/netbench-proxy-arm64" /data/local/tmp/netbench-proxy >/dev/null || exit 1
adbs shell chmod 755 /data/local/tmp/netbench-proxy
adbs shell 'nohup /data/local/tmp/netbench-proxy > /data/local/tmp/netbench-proxy.log 2>&1 &'
adbs forward tcp:18080 tcp:18080 >/dev/null
# Phone-side safety net: if this PC dies mid-run, Wi-Fi comes back after 4 h, once no call is active.
# Pre-flight: no stale timer from an earlier run may outlive this one.
kill_timers "pre-flight" || { say "a stale phone-side timer would not die: not switching Wi-Fi off"; exit 1; }
adbs shell 'nohup sh -c "sleep 14400; while dumpsys telephony.registry | grep -q \"mCallState=[12]\"; do sleep 15; done; svc wifi enable" >/dev/null 2>&1 & echo $! > /data/local/tmp/netbench-timer.pid'

# The guard's own status, not tee's: a failed guard must stop the Wi-Fi switch.
env -u GUARD_ALLOW_CALL "$GUARD" start | tee -a "$LOG"
[ "${PIPESTATUS[0]}" -eq 0 ] || { say "guard failed: not switching Wi-Fi off"; exit 1; }
adbs shell svc wifi disable
say "Wi-Fi disabled; waiting for the cell network"
for i in $(seq 1 30); do
  IP=$(curl -s --max-time 10 --proxy socks5h://127.0.0.1:18080 https://api.ipify.org)
  [ -n "$IP" ] && [ "$IP" != "$HOME_IP" ] && break
  sleep 2
done
say "exit IP via phone: ${IP:-none} (home $HOME_IP)"
[ -n "${IP:-}" ] && [ "$IP" != "$HOME_IP" ] || { say "no cell exit IP: aborting"; exit 1; }

(cd "$HERE/harness" && exec ./netbench run --network lte --proxy socks5h://127.0.0.1:18080 --out-dir "$RESULTS" --run-id "$RUN_ID" "$@") >>"$RESULTS/$RUN_ID.console.log" 2>&1 &
HPID=$!
say "harness pid $HPID"
while kill -0 "$HPID" 2>/dev/null; do
  sleep 60
  if wifi_on; then say "Wi-Fi came back on during the run: stopping it (results after this point would not be LTE)"; kill "$HPID"; break; fi
done
wait "$HPID" 2>/dev/null
say "harness finished (exit $?)"
