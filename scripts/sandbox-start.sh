#!/bin/sh
# 在容器内启动当前 worktree；编译由 bootRun/Vite 完成，无需单独构建动作。
set -eu
cd /workspace
state=/tmp/dap-runtime
mkdir -p "$state"
if [ -f "$state/supervisor.pid" ] && kill -0 "$(cat "$state/supervisor.pid")" 2>/dev/null; then
  old_pid=$(cat "$state/supervisor.pid")
  kill "$old_pid"
  attempts=0
  while kill -0 "$old_pid" 2>/dev/null; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 30 ]; then echo 'Previous sandbox startup did not stop' >&2; exit 1; fi
    sleep 1
  done
fi
echo $$ > "$state/supervisor.pid"
rm -f "$state/ready" "$state/failed"
pids=''
cleanup() {
  touch "$state/failed"
  for pid in $pids; do kill -TERM "-$pid" 2>/dev/null || true; done
  rm -f "$state/ready" "$state/supervisor.pid"
}
trap cleanup EXIT
trap 'exit 143' TERM INT
echo '[sandbox] Installing Linux dependencies (cached)'
sync_node() {
  project=$1
  fingerprint=$(sha256sum "$project/package-lock.json" | cut -d ' ' -f 1)
  stamp="$project/node_modules/.dap-lock-hash"
  if [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$fingerprint" ]; then
    npm --prefix "$project" ci
    echo "$fingerprint" > "$stamp"
  fi
}
sync_node dap-front > "$state/front-install.log" 2>&1
sync_node dap-test > "$state/test-install.log" 2>&1
(cd dap-agent && UV_PROJECT_ENVIRONMENT=/workspace/dap-agent/.venv-linux uv sync --frozen) > "$state/agent-install.log" 2>&1
setsid sh -c 'cd dap-server && exec gradle bootRun --no-daemon --console=plain' > "$state/backend.log" 2>&1 &
backend_pid=$!; pids="$pids $backend_pid"
setsid npm --prefix dap-front run dev -- --host 0.0.0.0 > "$state/frontend.log" 2>&1 &
front_pid=$!; pids="$pids $front_pid"
setsid sh -c 'cd dap-agent && UV_PROJECT_ENVIRONMENT=/workspace/dap-agent/.venv-linux exec uv run --no-sync dap-agent' > "$state/agent.log" 2>&1 &
agent_pid=$!; pids="$pids $agent_pid"
deadline=$(( $(date +%s) + ${DAP_START_TIMEOUT_SEC:-600} ))
while :; do
  for pid in $pids; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo '[sandbox] Service exited before becoming ready'
      tail -n 60 "$state/backend.log" "$state/frontend.log" "$state/agent.log"
      exit 1
    fi
  done
  if curl -fsS "$DAP_TEST_UI_URL/login" >/dev/null 2>&1 &&
     curl -s -o /dev/null --fail-with-body "$DAP_TEST_API_URL/api/auth/login" -X OPTIONS 2>/dev/null &&
     curl -fsS "http://127.0.0.1:${PORT:-8202}/openapi.json" >/dev/null 2>&1; then
    touch "$state/ready"
    echo '[sandbox] Backend, frontend and Agent ready'
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo '[sandbox] Services did not become ready before timeout'
    tail -n 60 "$state/backend.log" "$state/frontend.log" "$state/agent.log"
    exit 1
  fi
  sleep 2
done
# supervisor 存活期间保持服务与 readiness 一致。
while kill -0 "$backend_pid" && kill -0 "$front_pid" && kill -0 "$agent_pid"; do sleep 2; done
exit 1
