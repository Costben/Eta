#!/bin/bash
set -euo pipefail
# Restore only an explicitly named profile copy; preserve the modified project profile.
if [ "$#" -ne 1 ]; then
    printf 'usage: %s PROFILE_COPY\n' "$0" >&2
    exit 2
fi
VD_COPY="$1"
python3 - "$VD_COPY" "$(dirname "$0")/profile.json" <<'PY'
import json
import sys
from pathlib import Path
target = Path(sys.argv[1]).resolve()
modified = Path(sys.argv[2]).resolve()
if target == modified:
    raise SystemExit('select a separate rollback copy')
target.write_text(json.dumps({'power_strategy': 'sleep'}, indent=2) + '\n')
print('ROLLBACK_PROFILE_RESTORED power_strategy=sleep')
PY
