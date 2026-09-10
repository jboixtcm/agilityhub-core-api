"""Disposable E3-T06 stack checks; run after the development image build."""
import json
import os
from pathlib import Path
import shlex
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[3]
ENV = dict(os.environ, COMPOSE_PROJECT_NAME="e3-t06", SERVER_PORT="18080", MONGO_PORT="27018",
           OIDC_MASTER_KEY="", OIDC_LEARN_CLIENT_SECRET="")
COMPOSE = ["docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / "compose.yaml")]


def run(args, expected=0, env=ENV):
    print("$ " + shlex.join(args), flush=True)
    result = subprocess.run(args, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end="", flush=True)
    print("exit=" + str(result.returncode), flush=True)
    assert result.returncode == expected, (args, result.returncode)
    return result.stdout


def response(path, expected, headers=None, method="GET"):
    request = urllib.request.Request("http://127.0.0.1:18080" + path, headers=headers or {}, method=method)
    try:
        result = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        result = error
    with result:
        body = json.load(result)
        print(f"HTTP {method} {path} {result.status}: {json.dumps(body)}", flush=True)
        assert result.status == expected
        return body


def fresh():
    run(COMPOSE + ["ps"])
    run(["curl", "-4", "-fsS", "-i", "http://127.0.0.1:18080/api/v1/health"])
    assert response("/api/v1/health", 200, {"Host": "app.agilitycanic.cat"})["status"] == "UP"
    assert response("/api/v1/branding", 404, {"Host": "unknown.example.test"})["code"] == "UNKNOWN_HOST"
    assert response("/api/v1/health", 405, {"Idempotency-Key": "ignored-for-health"}, "POST")["code"] == "METHOD_NOT_ALLOWED"
    run(COMPOSE + ["exec", "-T", "mongo", "mongosh", "--quiet", "agilityhub", "--eval",
        'const counts = db.getCollectionNames().sort().map(name => ({collection: name, documents: db.getCollection(name).countDocuments({})})); printjson(counts); if (counts.some(item => item.documents !== 0)) { quit(1); }'])
    run(["docker", "ps", "--format", "{{.Names}} {{.Status}} {{.Ports}}"])
    for name in ["e3-t06-api-1", "e3-t06-mongo-1", "agilityhub-core-api-mongo-1"]:
        assert run(["docker", "inspect", "--format", "{{.State.Health.Status}}", name]).strip() == "healthy"
    print("PASS: empty database, host-independent health, unknown host 404, POST health 405; Mongo 27018 coexists with 27017.")


def probe():
    # The failure injection is only loaded into the disposable container from a temporary jar.
    source = r'''package com.agilityhub.core.shared.api;
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class FailureProbe implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                if ("true".equals(request.getParameter("forcedFailure"))
                        || java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/e3-t06-failure"))) {
                    throw new IllegalStateException("E3-T06 forced fictional failure");
                }
                return true;
            }
        }).addPathPatterns("/api/v1/health");
    }
}
'''
    with tempfile.TemporaryDirectory(prefix="e3-t06-final-probe-", dir="/private/tmp") as directory:
        temp = Path(directory)
        (temp / "FailureProbe.java").write_text(source)
        print("Temporary probe source (never added to src or the production image):\n" + source)
        with zipfile.ZipFile(ROOT / "target/agilityhub-core-api-0.1.0-SNAPSHOT.jar") as jar:
            for name in jar.namelist():
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    jar.extract(name, temp)
        run(["javac", "-cp", str(temp / "BOOT-INF/lib/*"), "-d", str(temp / "classes"), str(temp / "FailureProbe.java")])
        run(["jar", "cf", str(temp / "probe.jar"), "-C", str(temp / "classes"), "."])
        override = temp / "override.yaml"
        override.write_text("services:\n  api:\n    entrypoint: [java, '-Dloader.path=/app/e3-probe.jar', '-cp', /app/app.jar, org.springframework.boot.loader.launch.PropertiesLauncher]\n    volumes:\n      - " + str(temp / "probe.jar") + ":/app/e3-probe.jar:ro\n")
        injected = COMPOSE + ["-f", str(override)]
        try:
            run(injected + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "100", "api"])
            body = response("/api/v1/health?forcedFailure=true", 500)
            assert body["code"] == "INTERNAL_ERROR" and body["traceId"] in body["message"]
            assert body["details"] == {}
            logs = run(COMPOSE + ["logs", "api"])
            errors = [line for line in logs.splitlines() if " ERROR " in line]
            assert len(errors) == 1 and "traceId=" + body["traceId"] in errors[0]
            assert "IllegalStateException: E3-T06 forced fictional failure" in logs
            assert "FailureProbe$1.preHandle" in logs
            print("PASS: exactly one ERROR with response traceId and the full original stack.")
            run(["/bin/sh", "-c", shlex.join(COMPOSE + ["logs", "api"]) + " | grep -c ERROR"])
            run(COMPOSE + ["exec", "-T", "api", "touch", "/tmp/e3-t06-failure"])
            deadline = time.monotonic() + 100
            while time.monotonic() < deadline:
                health = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Health.Status}}", "e3-t06-api-1"], text=True).strip()
                if health == "unhealthy":
                    break
                time.sleep(2)
            assert health == "unhealthy", health
            run(injected + ["up", "-d", "--no-deps", "--wait", "--wait-timeout", "20", "api"], expected=1)
            run(COMPOSE + ["ps"])
            run(COMPOSE + ["exec", "-T", "api", "rm", "/tmp/e3-t06-failure"])
        finally:
            run(COMPOSE + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "100", "api"])
        assert response("/api/v1/health?forcedFailure=true", 200)["status"] == "UP"
        inspect = json.loads(subprocess.check_output(["docker", "inspect", "e3-t06-api-1"], text=True))[0]
        assert inspect["Config"]["Entrypoint"] == ["java", "-jar", "/app/app.jar"]
        assert all("e3-probe" not in mount["Destination"] for mount in inspect["Mounts"])
        print("PASS: real healthcheck became unhealthy, compose --wait failed, production entrypoint restored and healthy; probe removed.")



def consumer():
    run(COMPOSE + ["down", "-v"])
    env = dict(ENV, COMPOSE_PROJECT_NAME="e3-t06-consumer", CORE_IMAGE="e3-t06-api:latest",
               CONSUMER_PORT="18080", SEED_PASSWORD=secrets.token_urlsafe(24))
    compose = ["docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / "docker-compose.consumer.yml")]
    try:
        run(compose + ["up", "-d", "--wait", "--wait-timeout", "180"], env=env)
        run(compose + ["ps", "-a"], env=env)
        assert response("/api/v1/health", 200)["status"] == "UP"
        check = json.loads(subprocess.check_output(["docker", "inspect", "e3-t06-consumer-core-1"], text=True))[0]
        assert check["State"]["Health"]["Status"] == "healthy"
        assert check["Config"]["Healthcheck"]["Test"] == ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1"]
        assert run(["docker", "port", "e3-t06-consumer-mongo-1", "27017/tcp"]).strip() == "127.0.0.1:27018"
        print("PASS: consumer seeded, healthy with the explicit public-health check and MONGO_PORT=27018.")
    finally:
        run(compose + ["down", "-v"], env=env)
    run(["docker", "ps", "--format", "{{.Names}} {{.Status}} {{.Ports}}"])
    print("PASS: task containers and volumes removed; original stack retained.")

if __name__ == "__main__":
    {"fresh": fresh, "probe": probe, "consumer": consumer}[sys.argv[1]]()
