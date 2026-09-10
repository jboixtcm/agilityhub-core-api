#!/usr/bin/env python3
"""Capture a command's complete sanitized output and exact exit status for the report."""
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

directory = Path(__file__).resolve().parent
name, *command = sys.argv[1:]
with tempfile.TemporaryFile(mode="w+") as private:
    result = subprocess.run(command, stdout=private, stderr=subprocess.STDOUT)
    private.seek(0)
    output = private.read()
output = re.sub(r"\beyJ[A-Za-z0-9_.-]+", "eyJ…[truncated]", output)
output = re.sub(r"\$2[aby]\$[0-9]+\$[./A-Za-z0-9]+", "bcrypt…[truncated]", output)
output = re.sub(r"(?i)\b[0-9a-f]{12,}\b", lambda m: m[0][:6] + "…[truncated]", output)
output = re.sub(r"(?m)^index [0-9a-f]+\.\.[0-9a-f]+", "index [truncated]..[truncated]", output)
output = re.sub(r"(?i)([?&](?:t|token|signature|code)=)[^&\s]+", r"\1[truncated]", output)
output = re.sub(r"(?i)(Bearer )[^\s\"']+", r"\1[truncated]", output)
output = re.sub(r"(?<![\w.-])(?!T_|E[0-9]+_)[A-Za-z0-9_-]{43,}(?![\w.-])", "[truncated]", output)
(directory / (name + ".log")).write_text(output)
(directory / (name + ".json")).write_text(json.dumps({"command": shlex.join(command), "exitCode": result.returncode}, indent=2) + "\n")
print("Command:", shlex.join(command))
print("Exit code:", result.returncode)
print("\n".join(output.splitlines()[-40:]) or "(no output)")
sys.exit(result.returncode)
