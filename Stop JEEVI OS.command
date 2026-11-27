#!/bin/bash
# Double-click in Finder to stop JEEVI OS.
cd "$(dirname "$0")" || exit 1
bash "bin/jeevi-stop.sh"
echo ""
echo "You can close this window."
