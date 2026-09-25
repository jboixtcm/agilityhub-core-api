#!/usr/bin/env python3
"""E3-T10 steps 13-14: `club:apply` of the new Cànic seed on a club holding the previous one, twice.

Starts only the `mongo` service of compose.yaml under a random project name and port (the developer's own stacks are
untouched), then runs on the host, with the jar built by `./mvnw -q clean verify`:
  bin/core club:apply target/seed-before/club-canic.yaml   (the seed of HEAD; round 1: onPrimary #FFFFFF and Pack 6,
                                                           round 2: Pack 10 still «només un cop · …»)
  bin/core club:apply seeds/club-canic.yaml                (x2: the changes of this task, then nothing)
Prints each command's exit code and output; SEED_PASSWORD is random and never printed. Removes the stack at the end.
Adapted from roadmap/evidence/E3-T07/seeds_twice.py. Run from the repository root.
"""
import base64
import os
import secrets
import shutil
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BEFORE = ROOT / "target/seed-before"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    shutil.rmtree(BEFORE, ignore_errors=True)
    shutil.copytree(ROOT / "seeds/pages", BEFORE / "pages")
    previous = subprocess.run(["git", "show", "HEAD:seeds/club-canic.yaml"], cwd=ROOT, check=True, text=True, stdout=subprocess.PIPE).stdout
    (BEFORE / "club-canic.yaml").write_text(previous, encoding="utf-8")
    commands = [("club:apply", str(BEFORE / "club-canic.yaml")), ("club:apply", "seeds/club-canic.yaml"), ("club:apply", "seeds/club-canic.yaml")]
    project = "e3-t10-seed-" + secrets.token_hex(4)
    mongo_port = str(free_port())
    password = secrets.token_urlsafe(32)
    base = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "TMPDIR", "JAVA_HOME", "DOCKER_HOST",
                                        "DOCKER_CONTEXT", "DOCKER_CONFIG") if k in os.environ}
    compose = ["docker", "compose", "--env-file", os.devnull, "--project-name", project, "-f", str(ROOT / "compose.yaml")]
    compose_env = dict(base, MONGO_PORT=mongo_port)
    cli_env = dict(base, SPRING_PROFILES_ACTIVE="local", MONGODB_HOST="127.0.0.1", MONGODB_PORT=mongo_port,
                   MONGODB_DATABASE="e3_t10_seed", MONGODB_REPLICA_SET="rs0", SEED_PASSWORD=password,
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
        for index, command in enumerate(commands, 1):
            run = subprocess.run(["bin/core", *command], cwd=ROOT, env=cli_env, text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
            output = run.stdout.replace(password, "[redacted]")
            shown = " ".join(command).replace(str(ROOT) + "/", "")
            print(f"\n=== run {index}: $ bin/core {shown} -> exit {run.returncode}", flush=True)
            print(output.rstrip(), flush=True)
            failures += run.returncode != 0
    finally:
        down = subprocess.run([*compose, "down", "--volumes", "--remove-orphans", "--timeout", "15"], cwd=ROOT,
                              env=compose_env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(f"\n$ docker compose (project {project}) down --volumes -> exit {down.returncode}", flush=True)
        shutil.rmtree(BEFORE, ignore_errors=True)
    print(f"seed_apply exit code {1 if failures else 0}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
