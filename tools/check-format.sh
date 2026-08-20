#!/usr/bin/env bash
#
# Formatting gate. Enforces the four rules .editorconfig already declares:
#
#   1. no trailing whitespace
#   2. a final newline
#   3. LF line endings, never CRLF
#   4. no leading tabs in Java or XML
#
# Deliberately a shell script rather than a Maven plugin. The rules are worth
# enforcing; a build-plugin dependency, its version, and its configuration
# surface are not worth carrying to enforce them. This runs in about a second,
# has no dependency beyond git, grep and perl, and cannot fail to resolve.
#
# Portability: must run on a developer's macOS machine as well as on a CI
# runner. That rules out `mapfile` (bash 4+; macOS ships bash 3.2) and
# `grep -P` (GNU only; macOS ships BSD grep). Everything here is POSIX or
# bash 3.2.
#
# Usage:
#   tools/check-format.sh          report violations, exit 1 if any
#   tools/check-format.sh --fix    fix them in place, then report what changed
#
set -euo pipefail

cd "$(dirname "$0")/.."

FIX=0
[ "${1:-}" = "--fix" ] && FIX=1

# Tracked text files. NUL-delimited so a filename containing a newline cannot
# split one path into two.
FILES=()
while IFS= read -r -d '' path; do
    FILES+=("$path")
done < <(git ls-files -z -- \
    '*.java' '*.xml' '*.yml' '*.yaml' '*.json' '*.md' '*.properties' '*.sh' '*.sql' \
    ':!:mvnw' ':!:mvnw.cmd' ':!:.mvn/wrapper/*')

if [ "${#FILES[@]}" -eq 0 ]; then
    echo "format: no tracked text files matched"
    exit 0
fi

# Literal control characters, built with ANSI-C quoting so the patterns work
# under BSD grep as well as GNU grep.
TAB=$'\t'
CR=$'\r'

violations=0

report() {
    printf '%s\n' "$1"
    violations=$((violations + 1))
}

for f in "${FILES[@]}"; do
    [ -f "$f" ] || continue

    if [ "$FIX" -eq 1 ]; then
        # Order matters: strip CR first so trailing-whitespace removal sees
        # the real end of line.
        perl -i -pe 's/\r$//; s/[ \t]+$//' "$f"
        # Exactly one trailing newline.
        perl -i -0777 -pe 's/\n*\z/\n/' "$f"
        case "$f" in
            *.java|*.xml) perl -i -pe 's/^\t+/"    " x length($&)/e' "$f" ;;
        esac
        continue
    fi

    while IFS= read -r hit; do
        case "$hit" in
            '') ;;
            *) report "trailing whitespace:   $f:${hit%%:*}" ;;
        esac
    done < <(grep -nE "[ ${TAB}]+\$" "$f" || true)

    if grep -q "${CR}\$" "$f" 2>/dev/null; then
        report "CRLF line ending:      $f"
    fi

    if [ -s "$f" ] && [ "$(tail -c 1 "$f" | wc -l | tr -d ' ')" -eq 0 ]; then
        report "missing final newline: $f"
    fi

    case "$f" in
        *.java|*.xml)
            while IFS= read -r hit; do
                case "$hit" in
                    '') ;;
                    *) report "leading tab:           $f:${hit%%:*}" ;;
                esac
            done < <(grep -n "^${TAB}" "$f" || true)
            ;;
    esac
done

if [ "$FIX" -eq 1 ]; then
    if git diff --quiet; then
        echo "format: nothing to fix"
    else
        echo "format: fixed the following files"
        git diff --name-only | sed 's/^/  /'
    fi
    exit 0
fi

if [ "$violations" -gt 0 ]; then
    echo
    echo "$violations formatting violation(s). Run: tools/check-format.sh --fix"
    exit 1
fi

echo "format: ${#FILES[@]} files checked, no violations"
