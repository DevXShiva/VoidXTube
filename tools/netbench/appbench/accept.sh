#!/bin/bash
# Acceptance of the planner and HLS for VOD (docs/player-sources/PLANNER.md section 4): the switches
# as they would ship ("on") against the same build without them ("off"), per network.
#   accept.sh wifi smoke|rest
#   ../pixel-lte-wrap.sh <log> appbench/accept.sh lte smoke|rest     (LTE: inside the wrapper)
# Read the smoke before the rest starts; then: python3 appbench/accept_report.py lte wifi.
# Needs NETBENCH_SERIAL (appbench refuses to run without it); results go to $NETBENCH_DATA.
set -u
NET=${1:?network: wifi or lte}
STAGE=${2:-rest}
cd "$(dirname "$0")"
ON="--prop debug.arc.planner=1 --prop debug.arc.hls_vod=1"
# Explicit, so "off" stays off once a build has the switches on by default.
OFF="--prop debug.arc.planner=0 --prop debug.arc.hls_vod=0"
KIDS=_WB5hh7WOb4,e_04ZrNroTo,9DqETNOlcDE,pZw9veQ76fo,717IVcyUrNE
CTRL=0e3GPea1Tyg,MgNrAu2pzNs,Vop-h-u9B4o,MT8tg5b3b8E,LXb3EKWsInQ
CAT=MeJVWBSsPAY,WaOKSUlf4TM,HtVdAasjOgU,qkO6iBwcoe4,5yx6BWlEVcY,v03RjDNwG1o
NEG=j9epFget1W8,6SJNVb0GnPI,yZIXLfi8CZQ
R="python3 appbench.py --network $NET"
A="acc-$NET"
if [ "$STAGE" = smoke ]; then
  $R --sources WEB_EMBED $OFF --videos wGltuo1B1sM --run-id $A-s-embed-progressive || exit 1
  # WEB_EMBED answers about one request in three SABR-only + HLS (14 of 49 on the Pixel), the
  # only answers played over HLS: repeat to reach some.
  $R --sources WEB_EMBED $ON --videos dQw4w9WgXcQ,wGltuo1B1sM --repeat 3 --play-s 60 --run-id $A-s-embed-hls || exit 1
  $R --sources RING $ON --videos wGltuo1B1sM,WaOKSUlf4TM,6SJNVb0GnPI --play-s 60 --run-id $A-s-plan || exit 1
  exit 0
fi
$R --sources RING $ON --videos $KIDS --repeat 3 --run-id $A-kids-on || exit 1
$R --sources RING $OFF --videos $KIDS --repeat 3 --run-id $A-kids-off || exit 1
$R --sources RING $ON --videos $CTRL,$CAT --play-s 60 --run-id $A-cat-on || exit 1
$R --sources RING $OFF --videos $CTRL,$CAT --play-s 60 --run-id $A-cat-off || exit 1
$R --sources RING $ON --videos $NEG --play-s 30 --run-id $A-neg-on || exit 1
$R --sources RING $OFF --videos $NEG --play-s 30 --run-id $A-neg-off || exit 1
# Readiness: WEB_EMBED forced (its answers carry pre-rolls), >=15 ad-bearing answers.
$R --sources WEB_EMBED $ON --videos $KIDS,dQw4w9WgXcQ,kJQP7kiw5Fk,5KLPxDtMqe8,MgNrAu2pzNs,LXb3EKWsInQ --repeat 3 --play-s 60 --run-id $A-readiness || exit 1
# Kids -> ordinary in one process (state carries over, like a user).
$R --sources RING $ON --keep-process --videos _WB5hh7WOb4,dQw4w9WgXcQ,e_04ZrNroTo,kJQP7kiw5Fk,wGltuo1B1sM,5KLPxDtMqe8 --play-s 60 --run-id $A-seq || exit 1
# Recovery: one playback episode's media refused (a synthetic 403), then the recovery walk.
$R --sources RING $ON --prop debug.arc.poison_once_itag=any --videos dQw4w9WgXcQ,_WB5hh7WOb4 --repeat 3 --play-s 60 --run-id $A-recovery-on || exit 1
$R --sources RING $OFF --prop debug.arc.poison_once_itag=any --videos dQw4w9WgXcQ,_WB5hh7WOb4 --repeat 3 --play-s 60 --run-id $A-recovery-off
