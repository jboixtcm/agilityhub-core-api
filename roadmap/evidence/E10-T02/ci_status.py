"""Read published CI without exposing a full commit identifier in prose evidence."""
import json
import subprocess

head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
print(f"Current published HEAD: {head[:8]}...[truncated]", flush=True)
result = subprocess.run([
    "gh", "run", "list", "--repo", "jboixtcm/agilityhub-core-api", "--commit", head,
    "--json", "databaseId,conclusion,status,url,displayTitle", "--limit", "20"
], check=True, capture_output=True, text=True)
runs = json.loads(result.stdout)
print(json.dumps(runs, indent=2))
print("Final session commit is created by the publisher after executor handoff; its entire CI is pending.")
