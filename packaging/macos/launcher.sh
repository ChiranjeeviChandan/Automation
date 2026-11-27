#!/bin/bash
# ============================================================
# JEEVI OS.app (the one inside JeeviOS-macOS.dmg) - what a double-click runs.
#
# First run (no Jeevi in ~/JeeviOS yet): opens Terminal on bootstrap.sh, which checks the Mac,
# installs what is missing and starts Jeevi. Terminal on purpose: the first install takes
# minutes and may ask for the Mac password once, so the person must SEE what happens.
# Every run after that: bin/jeevi-start.sh, which only opens the window when Jeevi is already
# up and starts it first when it is not. It never restarts a running server.
#
# Unlike the in-repo "JEEVI OS.app" (which must stay inside the repo folder), this bundle can
# live in /Applications: it finds Jeevi through JEEVI_HOME, default ~/JeeviOS.
# ============================================================
JEEVI_HOME="${JEEVI_HOME:-$HOME/JeeviOS}"
RES="$(cd "$(dirname "$0")/../Resources" && pwd)"

if [ -d "${JEEVI_HOME}/.git" ] && [ -x "${JEEVI_HOME}/.venv/bin/python" ] && [ -x "${JEEVI_HOME}/bin/jeevi-start.sh" ]; then
  exec "${JEEVI_HOME}/bin/jeevi-start.sh"
fi

# macOS asks once whether "JEEVI OS" may control Terminal. If the person clicks Don't Allow
# (or the prompt never shows), osascript fails and nothing would happen at all: fall back to
# putting the command on the clipboard, opening Terminal, and saying "paste it".
# Not `open -a Terminal bootstrap.sh`: a script copied off the downloaded .dmg carries the
# quarantine flag, and Gatekeeper blocks it when it is opened through LaunchServices.
if ! osascript - "${RES}/bootstrap.sh" "${JEEVI_HOME}" <<'AS' >/dev/null 2>&1
on run argv
  tell application "Terminal"
    activate
    do script "JEEVI_HOME=" & quoted form of (item 2 of argv) & " bash " & quoted form of (item 1 of argv)
  end tell
end run
AS
then
  # %q escapes spaces and quotes in a form both bash and zsh (the Terminal default) accept.
  printf 'JEEVI_HOME=%q bash %q\n' "${JEEVI_HOME}" "${RES}/bootstrap.sh" | pbcopy
  open -a Terminal
  osascript -e 'display alert "JEEVI OS" message "Terminal is open. Paste (Cmd+V) and press Enter to install JEEVI OS.\n\nTerminal da mo. Dan (Cmd+V) roi bam Enter de cai JEEVI OS."' >/dev/null 2>&1 || true
fi
