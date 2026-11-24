#!/bin/sh
set -eu
state=/tmp/dap-runtime
deadline=$(( $(date +%s) + ${DAP_START_TIMEOUT_SEC:-600} ))
while [ ! -f "$state/ready" ]; do
  if [ -f "$state/failed" ] || [ "$(date +%s)" -ge "$deadline" ]; then
    echo '[sandbox] Test environment startup failed; tests were not executed.' >&2
    for file in "$state"/*.log; do [ ! -f "$file" ] || tail -n 40 "$file" >&2; done
    exit 78
  fi
  sleep 2
done
cd /workspace
exec npm --prefix dap-test test -- "$@"
