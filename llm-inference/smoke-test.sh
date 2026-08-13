#!/usr/bin/env bash

set -Eeuo pipefail

endpoint="${HARNESS_LLM_ENDPOINT:-${1:-http://localhost:11434}}"
model="${HARNESS_LLM_MODEL:-qwen2.5:7b}"

for command_name in curl python3; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "Missing required command: ${command_name}" >&2
        exit 1
    fi
done

echo "Checking ${endpoint} with model ${model}"

http_code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 5 --max-time 10 "${endpoint}/")"
if [[ "${http_code}" != "200" ]]; then
    echo "LLM endpoint is not healthy (HTTP ${http_code})." >&2
    exit 1
fi

if ! curl --silent --show-error --fail --max-time 15 "${endpoint}/api/tags" \
    | python3 -c '
import json
import os
import sys

expected = os.environ.get("HARNESS_LLM_MODEL", "qwen2.5:7b")
models = {item.get("name") for item in json.load(sys.stdin).get("models", [])}
if expected not in models and not any(name and name.startswith(expected + ":") for name in models):
    print(f"Model {expected!r} is not installed. Pull it before running this check.", file=sys.stderr)
    raise SystemExit(1)
'; then
    echo "Run: docker compose exec ollama ollama pull ${model}" >&2
    exit 1
fi

payload="$(python3 -c '
import json
import os

print(json.dumps({
    "model": os.environ.get("HARNESS_LLM_MODEL", "qwen2.5:7b"),
    "messages": [{"role": "user", "content": "Reply with the single word: ready"}],
    "stream": False,
}))
' )"

response="$(curl --silent --show-error --fail --max-time 120 \
    --header 'Content-Type: application/json' \
    --data "${payload}" \
    "${endpoint}/v1/chat/completions")"

printf '%s' "${response}" | python3 -c '
import json
import sys

content = json.load(sys.stdin)["choices"][0]["message"]["content"]
if not isinstance(content, str) or not content.strip():
    raise SystemExit("Model returned an empty response")
print(f"Smoke test passed: {content.strip()[:100]}")
'
