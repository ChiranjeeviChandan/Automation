#!/bin/bash
# ============================================================
# JEEVI OS - one-line install for macOS, no "cannot be opened" block
#
#   curl -fsSL https://raw.githubusercontent.com/blogminhquy/javis-os/main/packaging/macos/get-jeevi.sh | bash
#
# Downloads the same JeeviOS-macOS.dmg the website links to, copies JEEVI OS.app into
# Applications, then runs its first-time install right here in this Terminal window.
# Why no Gatekeeper prompt: macOS only checks files a browser saved (they carry a quarantine
# flag), and the app is not signed with an Apple Developer ID yet. A file curl downloads
# carries no such flag.
# ============================================================
set -euo pipefail

URL="https://github.com/blogminhquy/javis-os/releases/download/desktop/JeeviOS-macOS.dmg"
[ "$(uname -s)" = "Darwin" ] || { echo "This installer is for macOS. On Linux run ./install.sh from the repo."; exit 1; }

TMP="$(mktemp -d)"
MNT=""
cleanup() { [ -n "${MNT}" ] && hdiutil detach -quiet "${MNT}" 2>/dev/null; rm -rf "${TMP}"; }
trap cleanup EXIT

echo ""
echo "  JEEVI OS: dang tai bo cai... (downloading)"
curl -fL --progress-bar -o "${TMP}/JeeviOS-macOS.dmg" "${URL}"
MNT="$(hdiutil attach -nobrowse -readonly "${TMP}/JeeviOS-macOS.dmg" | awk -F'\t' '/\/Volumes\//{print $NF}')"
[ -d "${MNT}/JEEVI OS.app" ] || { echo "The disk image has no JEEVI OS.app"; exit 1; }

# /Applications when it is writable (an admin account), else ~/Applications.
DEST="/Applications"
[ -w "${DEST}" ] || { DEST="${HOME}/Applications"; mkdir -p "${DEST}"; }
# Replace only the app bundle; the brain lives in ~/JeeviOS and is never touched here.
rm -rf "${DEST}/JEEVI OS.app"
cp -R "${MNT}/JEEVI OS.app" "${DEST}/"
xattr -dr com.apple.quarantine "${DEST}/JEEVI OS.app" 2>/dev/null || true
echo "  Da chep JEEVI OS vao ${DEST} (copied to ${DEST})"

APP="${DEST}/JEEVI OS.app"
if [ -d "${HOME}/JeeviOS/.git" ] && [ -x "${HOME}/JeeviOS/.venv/bin/python" ]; then
  echo "  Jeevi da cai san, dang mo... (already installed, opening)"
  open "${APP}"
  exit 0
fi
# First install in THIS window. stdin is the curl pipe, so give the installer the keyboard
# back: install.sh asks for the Jeevi admin account and may ask for the Mac password.
bash "${APP}/Contents/Resources/bootstrap.sh" < /dev/tty
