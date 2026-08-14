#!/usr/bin/env bash
# Portal e2e: compose infra up -> gateway jar -> Playwright -> teardown.
# Prerequisite: the boot jar exists (./mvnw -DskipTests package or ./mvnw verify).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UI_DIR="$REPO_ROOT/ui"
COMPOSE="docker compose -f $REPO_ROOT/compose.e2e.yaml"
JAR="$(ls -t "$REPO_ROOT"/target/skills-gateway-*.jar 2>/dev/null | grep -v plain | head -1 || true)"

if [[ -z "$JAR" ]]; then
  echo "boot jar not found under target/ — run ./mvnw -DskipTests package first" >&2
  exit 1
fi

UPSTREAM_DIR="$(mktemp -d "${TMPDIR:-/tmp}/e2e-upstream.XXXXXX")"
GATEWAY_PID=""

cleanup() {
  [[ -n "$GATEWAY_PID" ]] && kill "$GATEWAY_PID" 2>/dev/null || true
  $COMPOSE down -v >/dev/null 2>&1 || true
  rm -rf "$UPSTREAM_DIR"
}
trap cleanup EXIT

# Upstream marketplace fixture (isolated from host git config: no GPG signing).
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
git init -q -b main "$UPSTREAM_DIR"
mkdir -p "$UPSTREAM_DIR/.claude-plugin" "$UPSTREAM_DIR/plugins/hello/skills/hello"
cat > "$UPSTREAM_DIR/.claude-plugin/marketplace.json" <<'EOF'
{
  "name": "e2e-marketplace",
  "owner": {"name": "E2E"},
  "plugins": [
    {"name": "hello", "source": "./plugins/hello", "description": "e2e"}
  ]
}
EOF
echo "# Hello" > "$UPSTREAM_DIR/plugins/hello/skills/hello/SKILL.md"
git -C "$UPSTREAM_DIR" add -A
git -C "$UPSTREAM_DIR" -c user.name=e2e -c user.email=e2e@example.com \
  -c commit.gpgsign=false commit -q -m "e2e marketplace fixture"

$COMPOSE up -d --wait

GATEWAY_PORT="${E2E_GATEWAY_PORT:-8081}"
mkdir -p "$UI_DIR/test-results"
DATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/e2e-data.XXXXXX")"
SERVER_PORT="$GATEWAY_PORT" \
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/skillsgateway" \
SPRING_DATASOURCE_USERNAME=skillsgateway \
SPRING_DATASOURCE_PASSWORD=skillsgateway \
SGW_OIDC_CLIENT_ID=e2e-client \
SGW_OIDC_CLIENT_SECRET=e2e-secret \
SGW_OIDC_AUTHORIZATION_URI="http://localhost:9090/default/authorize" \
SGW_OIDC_TOKEN_URI="http://localhost:9090/default/token" \
SGW_OIDC_JWK_SET_URI="http://localhost:9090/default/jwks" \
SKILLSGATEWAY_DATADIR="$DATA_DIR" \
SKILLSGATEWAY_ALLOWEDURLSCHEMES="http,https,file" \
java -jar "$JAR" > "$UI_DIR/test-results/gateway-e2e.log" 2>&1 &
GATEWAY_PID=$!

echo "waiting for gateway health..."
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:$GATEWAY_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    break
  fi
  sleep 1
done
curl -fsS "http://localhost:$GATEWAY_PORT/actuator/health" | grep -q '"UP"' || {
  echo "gateway did not become healthy"; tail -50 "$UI_DIR/test-results/gateway-e2e.log"; exit 1;
}

set +e
E2E_BASE_URL="http://localhost:$GATEWAY_PORT" E2E_UPSTREAM_URL="file://$UPSTREAM_DIR" \
  pnpm --dir "$UI_DIR" exec playwright test "$@"
RC=$?
set -e
node "$UI_DIR/scripts/fix-junit-classnames.mjs"
exit $RC
