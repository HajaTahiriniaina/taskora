#!/usr/bin/env bash
# PreToolUse hook for BackCodeur / FrontCodeur: blocks any write to test files.
# Exit code 2 = the tool call is refused and stderr is shown to the agent.
exec python3 -I -c '
import json, re, sys

TEST_PATH = re.compile(r"(^|/)(services/[^ ]*/src/test/|frontend/e2e/|backend/tests/)|\.spec\.ts\b|(^|/)e2e/")
WRITE_CMD = re.compile(r"(>|\btee\b|\bsed\s+-i|\bperl\s+-[a-z]*i|\brm\b|\bmv\b|\bcp\b|\btruncate\b|"
                       r"\bgit\s+(checkout|restore|rm|mv|stash|reset|apply)\b|\bpatch\b|\bspotless:apply\b|"
                       r"\beslint\b.*--fix|\bprettier\b.*--write)")

data = json.load(sys.stdin)
tool = data.get("tool_name", "")
inp = data.get("tool_input", {}) or {}

def deny(msg):
    sys.stderr.write("Refusé : " + msg + " Les tests sont la référence et appartiennent au Testeur. "
                     "Si un test te paraît faux, explique-le dans ton rapport au chef de pôle.\n")
    sys.exit(2)

if tool == "Bash":
    cmd = inp.get("command", "")
    if TEST_PATH.search(cmd) and WRITE_CMD.search(cmd):
        deny("cette commande écrit dans un fichier de test.")
    # Formatters/fixers on the whole tree would also rewrite tests.
    if re.search(r"\bspotless:apply\b", cmd) and "-DspotlessFiles" not in cmd:
        deny("spotless:apply sans -DspotlessFiles reformaterait aussi les tests ; cible les fichiers de src/main.")
    if re.search(r"\b(eslint\b.*--fix|prettier\b.*--write)\s+(\.|src/?|frontend/?)(\s|$)", cmd):
        deny("eslint --fix / prettier --write sur tout le dossier toucherait aussi les tests ; cible tes fichiers.")
else:
    path = inp.get("file_path") or inp.get("notebook_path") or ""
    if TEST_PATH.search(path):
        deny("écriture dans un fichier de test (" + path + ").")
sys.exit(0)
'
