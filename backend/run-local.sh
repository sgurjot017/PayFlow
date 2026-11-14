#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
env_file="backend/.env"
if [[ ! -f "$env_file" ]]; then
  printf 'Missing %s. Copy backend/.env.example and configure local values.\n' "$env_file" >&2
  exit 1
fi

# Load dotenv values without eval/source, so values with whitespace cannot become commands.
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \\#* ]] && continue
  [[ "$line" == *=* ]] || continue
  key="${line%%=*}"
  value="${line#*=}"
  key="${key//[[:space:]]/}"
  [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  export "${key}=${value}"
done < "$env_file"

exec ./gradlew :backend:run
