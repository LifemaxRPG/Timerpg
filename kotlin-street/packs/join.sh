#!/bin/sh
# Rebuild the split packs (GitHub refuses files over 100 MB): ./join.sh [out-dir]
set -e; cd "$(dirname "$0")"; out=${1:-.}
for z in Modern_Exteriors_v42.3.zip Modern_Interiors_v41.4.zip; do cat $z.part* > "$out/$z"; done
(cd "$out" && sha256sum -c "$OLDPWD/SHA256SUMS")
