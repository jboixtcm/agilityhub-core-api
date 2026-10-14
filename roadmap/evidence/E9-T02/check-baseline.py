#!/usr/bin/env python3
"""Verify the failed CI run belongs to the unchanged local product baseline."""
import json
import subprocess


def read(*command):
    return subprocess.check_output(command, text=True).strip()


head = read("git", "rev-parse", "HEAD")
run = json.loads(read("gh", "run", "view", "38518751588", "--json",
                      "headSha,status,conclusion,url,jobs"))
assert run["headSha"] == head, "CI run does not match HEAD"
assert run["status"] == "completed" and run["conclusion"] == "failure"
subprocess.run(["git", "diff", "--exit-code", "--", "src", "pom.xml", "bin",
                ".mvn", "mvnw", "mvnw.cmd", "docs/openapi/openapi.json"], check=True)
print(f"Current HEAD {head[:7]} matches CI run 38518751588.")
print("Product source, tests, build inputs and OpenAPI have no working-tree changes.")
print(f"Whole run: {run['status']}, {run['conclusion']}; {run['url']}")
for job in run["jobs"]:
    print(f"{job['name']}: {job['status']}, {job['conclusion']}")
