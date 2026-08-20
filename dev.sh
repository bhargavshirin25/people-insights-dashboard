#!/usr/bin/env bash
# Starts MySQL, the Spring Boot backend (:8080) and the Next frontend (:3000).
# Ctrl+C stops both servers (the MySQL container is left running).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

docker start people-insights-mysql >/dev/null 2>&1 || {
  echo "MySQL container not found — create it once with the docker run in docs/MYSQL.md" >&2
  exit 1
}

[ -d frontend/node_modules ] || (cd frontend && npm install)

trap 'kill 0' EXIT INT TERM

(cd backend && mvn -q spring-boot:run -Dspring-boot.run.profiles=local 2>&1 | sed 's/^/[api] /') &
(cd frontend && npm run dev 2>&1 | sed 's/^/[web] /') &
wait
