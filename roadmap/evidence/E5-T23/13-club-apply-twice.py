#!/usr/bin/env python3
"""E5-T23 verification (a copy of roadmap/evidence/E3-T11/seeds_twice.py; only the names and the command list differ):
run `bin/core club:apply seeds/club-canic.yaml` twice on a fresh disposable Mongo.

Starts only the `mongo` service of compose.yaml under a random project name and port
(the developer's own stacks are untouched), then runs, on the host, with the jar built
by `./mvnw -q clean verify`:
  bin/core club:apply seeds/club-canic.yaml          (x2)
Prints each command's exit code and its output; SEED_PASSWORD is random, never printed
(any occurrence is replaced by [redacted]). Removes the stack and its volume at the end.
Run from the repository root.
"""
import base64
import os
import secrets
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COMMANDS = [("club:apply", "seeds/club-canic.yaml")] * 2


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    project = "e5-t23-seeds-" + secrets.token_hex(4)
    mongo_port = str(free_port())
    password = secrets.token_urlsafe(32)
    base = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "TMPDIR", "JAVA_HOME", "DOCKER_HOST",
                                        "DOCKER_CONTEXT", "DOCKER_CONFIG") if k in os.environ}
    compose = ["docker", "compose", "--env-file", os.devnull, "--project-name", project, "-f", str(ROOT / "compose.yaml")]
    compose_env = dict(base, MONGO_PORT=mongo_port)
    cli_env = dict(base, SPRING_PROFILES_ACTIVE="local", MONGODB_HOST="127.0.0.1", MONGODB_PORT=mongo_port,
                   MONGODB_DATABASE="e5_t23_seeds", MONGODB_REPLICA_SET="rs0", SEED_PASSWORD=password,
                   AUTH_ISSUER="https://id.example.test", OIDC_LOGIN_URL="https://id.example.test/login",
                   SIGNUP_CAPABILITY_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                   LOGGING_LEVEL_ROOT="WARN", SPRING_MAIN_BANNER_MODE="off")
    failures = 0
    try:
        up = subprocess.run([*compose, "up", "-d", "--wait", "--wait-timeout", "180", "mongo"], cwd=ROOT,
                            env=compose_env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(f"$ docker compose (project {project}) up -d --wait mongo -> exit {up.returncode}", flush=True)
        if up.returncode:
            print(up.stdout)
            return 1
        for index, command in enumerate(COMMANDS, 1):
            run = subprocess.run(["bin/core", *command], cwd=ROOT, env=cli_env, text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
            output = run.stdout.replace(password, "[redacted]")
            print(f"\n=== run {index}: $ bin/core {' '.join(command)} -> exit {run.returncode}", flush=True)
            print(output.rstrip(), flush=True)
            failures += run.returncode != 0
    finally:
        down = subprocess.run([*compose, "down", "--volumes", "--remove-orphans", "--timeout", "15"], cwd=ROOT,
                              env=compose_env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(f"\n$ docker compose (project {project}) down --volumes -> exit {down.returncode}", flush=True)
    print(f"club_apply_twice exit code {1 if failures else 0}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
