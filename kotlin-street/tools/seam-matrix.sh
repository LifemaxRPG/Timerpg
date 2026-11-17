#!/usr/bin/env bash
# The seam test (desktop -Dmode=seam) over many views of the walk round the corner, one after another (one gradle at a
# time). quick: where in the mouth x which way at the phone's hour, plus day, night-rain-winter on the phone's screen;
# full: every place x hour x weather x season x screen x way. Prints each run's verdict and every failing line.
#   tools/seam-matrix.sh [quick|full]
set -u
cd "$(dirname "$0")/.."
out=desktop/build/preview/seam
mkdir -p "$out"
log="$out/matrix.txt"
: > "$log"
run() {
  local tag="m_$(echo "$*" | tr ' =' '_-' | tr -d -- '-D')"
  gradle -q --offline :desktop:preview -Dmode=seam "$@" -Dtag="$tag" > /dev/null 2>&1
  local f="$out/seam_$tag.txt"
  if [ ! -f "$f" ]; then echo "NO RESULT  $*" | tee -a "$log"; return; fi
  echo "$(tail -1 "$f")  $*" | tee -a "$log"
  grep "^FAIL\|^POP" "$f" | sed 's/^/    /' | tee -a "$log"
}
if [ "${1:-quick}" = full ]; then
  for at in west mid east; do for h in 13 16.8 17.6 21.5; do for wx in clear rain; do for se in summer winter; do
    for sz in preview phone; do for d in fwd back; do run -Dat=$at -Dhour=$h -Dwx=$wx -Dseason=$se -Dsize=$sz -Ddir=$d; done; done
  done; done; done; done
else
  for at in west mid east; do for d in fwd back; do run -Dat=$at -Dhour=17.6 -Ddir=$d; done; done
  for d in fwd back; do run -Dhour=13 -Dsize=phone -Ddir=$d; run -Dhour=21.5 -Dwx=rain -Dseason=winter -Dsize=phone -Ddir=$d; done
  run -Ddir=abort -Dsize=phone
fi
echo "matrix done: $(grep -c 'ALL PASS' "$log") pass, $(grep -c ' FAIL ' "$log") fail" | tee -a "$log"
