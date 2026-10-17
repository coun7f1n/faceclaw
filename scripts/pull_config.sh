#!/bin/bash
# Pull faceclaw's shared preferences off the attached device.
# Usage: pull_config.sh [output-file] [-f output-file] [adb-options...]
#
# Uses the adb-only settings port on every build. The exported XML contains
# ordinary settings only; credentials must be re-entered on another device.
set -euo pipefail

source "$(cd "$(dirname "$0")" && pwd)/adbutil.sh"

usage() {
    cat <<EOF
Usage: $(basename "$0") [output-file] [-f output-file] [adb-options...]

Pull ordinary faceclaw settings off the attached device into output-file
(default: faceclaw_settings.xml). Credentials are excluded and must be
re-entered on another device. The output file may be given as a bare
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
OUT="${ADBUTIL_FILE:-faceclaw_settings.xml}"
EXPORT_REMOTE=/sdcard/Android/data/$PACKAGE/files/faceclaw-settings-export.xml

adb_preflight "$PACKAGE" >/dev/null
RESULT="$(adb shell am broadcast -n "$PACKAGE/.FaceclawSettingsPortReceiver" \
    -a com.faceclaw.app.SETTINGS_EXPORT)"
if ! printf '%s' "$RESULT" | grep -q 'data="exported:'; then
    printf '%s\n' "$RESULT" >&2
    echo "Error: export broadcast did not report success (old app version?)" >&2
    exit 1
fi
adb pull "$EXPORT_REMOTE" "$OUT" >/dev/null
adb shell rm "$EXPORT_REMOTE"
echo "Pulled ordinary settings to $OUT; credentials were excluded"
