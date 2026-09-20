#!/usr/bin/env bash
# Guards scripts/boss.bat's :detect_and_route - the bare `boss <anything>` form.
#
# Scoped to that routine on purpose. The :urlencode injection is #1057, fixed by
# #1059; nothing here touches or duplicates it.
#
# Two defects are guarded:
#
#   1. `echo %arg% | findstr` substituted %arg% into the command line before cmd
#      parsed it, so an "&" in the argument started a second command. Verified on
#      Windows 11 against the pre-fix routine: a payload of
#      `x&echo side-effect>marker.txt` created the file.
#
#   2. Inside the `if exist "%arg%" (...)` block, %fullpath% and %ENCODED% were
#      expanded when cmd parsed the whole block - before the `set` and the `call`
#      that fill them ran. Both were empty, so every `boss <file>` and
#      `boss <folder>` launched `boss://file?path=` with nothing after it.
#      Verified on Windows 11: `boss://folder?path=` before, full path after.
#
# boss.bat is cmd and cannot be executed on this Linux runner, so these are
# source-shape assertions. Each one fails against the pre-fix file.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
bat="$root/scripts/boss.bat"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

# --- 1. no subshell in the detection path -----------------------------------
if grep -qE '^echo %arg% \|' "$bat"; then
    fail 'boss.bat pipes an unquoted %arg% through echo again - "&" starts a command'
fi
grep -qF 'if /i "%arg:~0,7%"=="http://"' "$bat" \
    || fail 'the http:// prefix test is no longer a plain string comparison'
grep -qF 'if not "%arg:.com=%"=="%arg%"' "$bat" \
    || fail 'the TLD tests are no longer plain string comparisons'

# --- 2. the deep link carries a path ----------------------------------------
# The file/folder branches must NOT sit inside a parenthesized block, or the
# values are expanded before they are assigned.
grep -qF ':detect_folder' "$bat" || fail 'the folder branch is not a label any more'
grep -qF ':detect_file' "$bat" || fail 'the file branch is not a label any more'
if grep -qE '^\s+call :urlencode "%fullpath%" ENCODED$' "$bat"; then
    fail 'the urlencode call is indented, i.e. back inside a parenthesized block'
fi

# --- 3. the failure message does not re-parse the value ---------------------
grep -qF 'echo Error: Could not determine type for: "%arg%"' "$bat" \
    || fail 'the failure message prints %arg% unquoted again'

echo 'CLI launcher detection tests passed'
