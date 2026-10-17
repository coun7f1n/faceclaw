#!/bin/bash
# Push a shared-preferences file to faceclaw on the attached device.
# Usage: push_config.sh [input-file] [-f input-file] [adb-options...]
# Validates the XML, force-stops the app (so a running instance doesn't
# overwrite the pushed file from its in-memory prefs), then installs it.
#
# Uses the adb-only settings port on every build. Credential keys are rejected
# rather than installed as plaintext; existing encrypted credentials remain.
set -euo pipefail

source "$(cd "$(dirname "$0")" && pwd)/adbutil.sh"

usage() {
    cat <<EOF
Usage: $(basename "$0") [input-file] [-f input-file] [adb-options...]

Import ordinary settings from input-file (default: faceclaw_settings.xml).
Credential keys are rejected; existing encrypted credentials are preserved.
The input file may be given as a bare
first argument or with -f anywhere. Any other arguments are passed through
to adb, e.g. -s SERIAL or -t ID to pick one of several attached devices.
EOF
}

adbutil_parse_args "$@"
if [ "$ADBUTIL_HELP" = 1 ]; then
    usage
    exit 0
fi

PACKAGE=com.faceclaw.app
IN="${ADBUTIL_FILE:-faceclaw_settings.xml}"
IMPORT_REMOTE=/sdcard/Android/data/$PACKAGE/files/faceclaw-settings-import.xml

if [ ! -f "$IN" ]; then
    echo "Error: $IN not found" >&2
    exit 1
fi

if command -v xmllint >/dev/null 2>&1; then
    xmllint --noout "$IN"
elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])' "$IN"
else
    echo "Error: need xmllint or python3 to validate XML" >&2
    exit 1
fi

adb_preflight "$PACKAGE" >/dev/null

adb shell am force-stop "$PACKAGE"

adb push "$IN" "$IMPORT_REMOTE" >/dev/null
RESULT="$(adb shell am broadcast -n "$PACKAGE/.FaceclawSettingsPortReceiver" \
    -a com.faceclaw.app.SETTINGS_IMPORT)"
if ! printf '%s' "$RESULT" | grep -q 'data="imported:'; then
    printf '%s\n' "$RESULT" >&2
    adb shell rm -f "$IMPORT_REMOTE"
    echo "Error: import broadcast did not report success (old app version?)" >&2
    exit 1
fi
echo "Imported ordinary settings from $IN; existing encrypted credentials were preserved"
