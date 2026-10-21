#!/bin/bash
# Usage: guard.sh app|start|net
#   app   : check app must hold focus
#   start : focus must be the check app, the launcher, or the owner's idle NewTube
#           (never a call screen / WhatsApp / shade / keyguard)
#   net   : only the call-state check (plus shade/awake info)
# Exit 0 = safe to act. Prints a one-line verdict.
# GUARD_ALLOW_CALL=1 (the owner's rule of 2026-09-29): an active call no longer fails app/start,
# for in-app opens and inputs only; the verdict then carries "incall". Every other check stays.
# Never set it for network toggles (pixel-lte-wrap.sh clears it): dropping Wi-Fi mid-call can cut
# a Wi-Fi-calling or WhatsApp call. `net` always requires no call.
# The phone is NETBENCH_SERIAL (its adb serial); without it the guard fails (never acts).
S=${NETBENCH_SERIAL:?set NETBENCH_SERIAL to the adb serial of the phone}
mode=${1:-app}
W=$(adb -s $S shell dumpsys window 2>/dev/null)
focus=$(printf '%s\n' "$W" | grep -a -m1 mCurrentFocus | sed 's/^ *//')
calls=$(adb -s $S shell dumpsys telephony.registry 2>/dev/null | grep -a mCallState | tr -d ' \r' | sort -u | tr '\n' ' ')
wake=$(adb -s $S shell dumpsys power 2>/dev/null | grep -a -m1 "mWakefulness=" | tr -d ' \r')
lua=$(adb -s $S shell dumpsys power 2>/dev/null | grep -a -m1 "^lastUserActivityTime=" | tr -d '\r')
# NotificationShade listed among visible input windows => shade / HUN / keyguard is up
shade_vis=$(printf '%s\n' "$W" | grep -a "visible windows:" | grep -a -c "NotificationShade")
# StatusBar thicker than 400px => expanded. Thickness is the frame's SHORT side: in landscape the
# bar is a 63x2424 strip down the side, not an expanded shade (false positive, 2026-09-28 22:12).
sb_tall=$(printf '%s\n' "$W" | grep -a "visible windows:" | grep -a -o "StatusBar, frame=\[Rect(0, 0 - [0-9]*, [0-9]*)" | sed 's/.* - //; s/)//' | awk -F', ' '{t=($1+0<$2+0)?$1:$2; if (t+0>400) print "tall"}' | head -1)
reason=""
incall=""
if [ "$calls" != "mCallState=0 " ]; then
  # An unreadable call state (an empty adb read) is never taken for "no call".
  if [ "${GUARD_ALLOW_CALL:-}" = 1 ] && [ "$mode" != net ] && [ -n "$calls" ]; then
    incall=" incall[$calls]"
  else
    reason="$reason call[$calls]"
  fi
fi
[ "$wake" = "mWakefulness=Awake" ] || reason="$reason notAwake[$wake]"
[ "$shade_vis" = "0" ] || reason="$reason shadeVisible"
[ -z "$sb_tall" ] || reason="$reason statusBarExpanded"
case "$mode" in
  app)
    printf '%s' "$focus" | grep -q -E "io.github.aleixrodriala.arc.(check|auth)/" || reason="$reason focusNotCheck"
    ;;
  start)
    # The launcher is whatever the phone's default home app is (not only the stock Pixel one).
    home=$(adb -s $S shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1 | tr -d '\r' | cut -d/ -f1)
    case "$home" in *.*) ;; *) home=com.google.android.apps.nexuslauncher ;; esac
    if printf '%s' "$focus" | grep -q -E "io.github.aleixrodriala.arc.(check|auth)/|com.google.android.apps.nexuslauncher/|${home//./\\.}/|io.github.aleixrodriala.arc/"; then :; else reason="$reason focusDisallowed"; fi
    printf '%s' "$focus" | grep -q -i -E "whatsapp|dialer|incall|telecom|NotificationShade|Keyguard" && reason="$reason focusDisruptive"
    ;;
  net) ;;
esac
if [ -z "$reason" ]; then
  echo "GUARD OK ($mode)$incall $focus | $calls| $wake | $lua"
  exit 0
else
  echo "GUARD FAIL ($mode):$reason | $focus | $lua"
  exit 1
fi
