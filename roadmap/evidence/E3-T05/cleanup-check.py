#!/usr/bin/env python3
"""Read-only check that the E3 rehearsal removed its disposable Docker resources."""
import subprocess

commands = {
    "containers": ["docker", "ps", "-a", "--filter", "name=e3-smoke-", "--format", "{{.Names}}"],
    "volumes": ["docker", "volume", "ls", "--filter", "name=e3-smoke-", "--format", "{{.Name}}"],
    "networks": ["docker", "network", "ls", "--filter", "name=e3-smoke-", "--format", "{{.Name}}"],
}
for kind, command in commands.items():
    result = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE)
    assert not result.stdout.strip(), "Rehearsal left disposable " + kind
    print("PASS no E3 smoke " + kind + " remain")
