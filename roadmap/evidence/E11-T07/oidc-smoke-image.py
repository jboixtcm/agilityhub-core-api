#!/usr/bin/env python3
"""E11-T07: run bin/oidc-smoke against an image on a disposable consumer Compose project.

Usage: python3 roadmap/evidence/E11-T07/oidc-smoke-image.py <image>

As in E1-T05's run: the minimal club and the fictional test accounts are seeded with the image's own CLI
(`club:apply seeds/club-minim.yaml` through the consumer seed service, then `identity:seed-test-accounts --club=minim`),
and bin/oidc-smoke signs in as admin@example.test. The seed password is generated here and passed only through the
environment; nothing secret is printed. The project, its volumes and network are removed at the end.
"""
import os
import secrets
import socket
import subprocess
import sys
import urllib.request
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


image = sys.argv[1]
project = "e11-t07-oidc-" + secrets.token_hex(4)
port = free_port()
env = dict(os.environ, CORE_IMAGE=image, CONSUMER_PORT=str(port), MONGO_PORT=str(free_port()), CONSUMER_DATABASE="oidc_smoke",
           CONSUMER_SCHEDULING_ENABLED="false", CONSUMER_LOG_LEVEL="ERROR", SEED_PASSWORD=secrets.token_urlsafe(32))
compose = ["docker", "compose", "--env-file", os.devnull, "--project-name", project, "-f", str(ROOT / "docker-compose.consumer.yml")]


def run(*args, **kwargs):
    print("COMMAND", " ".join(args[0] if isinstance(args[0], list) else args), flush=True)
    return subprocess.run(*args, env=env, check=True, **kwargs)


try:
    run([*compose, "up", "-d", "--wait", "--wait-timeout", "240"])
    run([*compose, "run", "--rm", "--no-deps", "-T", "--entrypoint", "java", "seed", "-jar", "/app/app.jar",
         "--core.command=identity:seed-test-accounts", "--club=minim"])
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/health", timeout=10) as response:
        print("HEALTH", response.status, response.read().decode(), flush=True)
    started = time.monotonic()
    smoke = subprocess.run([str(ROOT / "bin/oidc-smoke")], cwd=ROOT, text=True, capture_output=True,
                           env=dict(env, OIDC_SMOKE_BASE_URL=f"http://127.0.0.1:{port}", OIDC_SMOKE_EMAIL="admin@example.test",
                                    OIDC_SMOKE_PASSWORD=env["SEED_PASSWORD"]))
    print(smoke.stdout, end="")
    print(smoke.stderr, end="", file=sys.stderr)
    print(f"OIDC_SMOKE exit {smoke.returncode} in {time.monotonic() - started:.1f} s", flush=True)
    sys.exit(smoke.returncode)
finally:
    subprocess.run([*compose, "down", "-v", "--remove-orphans"], env=env, capture_output=True)
    print("CLEANUP project, volumes and network removed", flush=True)
