"""E6-T03 round 5 before-fix run. Copies the working tree to target/before-fix-e6t03-r5 (the working tree is never touched),
puts the round-4 code (HEAD) back in the copy, and runs the round-5 tests there. Each new test must fail on the round-4 code.

- Every production file this round changes gets its committed (HEAD, round 4) version in the copy, and the new
  `KeyedAnswers` is removed (HEAD's controllers never call it).
- Three test files use the new production API (`FollowupTransactions.keyed`, `FollowupContractAccess.owner`,
  `IdempotencyFilter.FOLLOWUP`), so they do not compile against round 4: `FollowupTransactionsTest` and
  `FollowupContractAccessTest` get their HEAD version in the copy, and `IdempotencyFilterFollowupRoutesTest` is left out.
  Their behaviour is covered by the ITs below, which only use HTTP and spies of beans HEAD already has.
- Run A (strict): `FollowupIT` and `E6ContractIT` as they are.
- Run B (soft): a second copy of `FollowupIT` in which the four overlap tests of round 5 use soft assertions, so every route
  of point 2 shows its round-4 answer instead of stopping at the first failing one. In that copy an exception that escapes
  MockMvc (a commit failure the round-4 filter rethrows, which a server answers with its own 500 page) is recorded as the
  outcome `500 UNCAUGHT <exception>`, and `Overlap.with(status)` falls back to the first answer instead of throwing.
No other test file is changed in the copies. Read-only git only (`git diff --name-only`, `git ls-files`, `git show HEAD:<path>`).
Run from the repository root: python3 roadmap/evidence/E6-T03/before-fix-round5.py
"""
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix-e6t03-r5"
BASE = "src/main/java/com/agilityhub/core/"
TESTS = "src/test/java/com/agilityhub/core/"
RESTORED = {BASE + "shared/api/IdempotencyFilter.java", BASE + "clubs/followup/api/TasksController.java",
            BASE + "clubs/followup/api/AttachmentsController.java", BASE + "clubs/followup/api/FollowupController.java",
            BASE + "clubs/followup/application/FollowupTransactions.java", BASE + "clubs/followup/application/FollowupContractAccess.java"}
ADDED = {BASE + "clubs/followup/api/KeyedAnswers.java"}
TEST_HEAD = {TESTS + "clubs/followup/application/FollowupTransactionsTest.java", TESTS + "clubs/followup/application/FollowupContractAccessTest.java"}
TEST_LEFT_OUT = TESTS + "shared/api/IdempotencyFilterFollowupRoutesTest.java"
FOLLOWUP_IT = TESTS + "clubs/followup/api/FollowupIT.java"
OVERLAP_TESTS = "T_10_25_twoOverlapping*+R_10_10_overlapping*+T_10_16_overlapping*+T_10_18_T_10_06_overlapping*"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


def copy_tree(tree):
    shutil.rmtree(tree, ignore_errors=True)
    tree.mkdir(parents=True)
    for name in ("src", ".mvn", "docs", "seeds"):
        shutil.copytree(ROOT / name, tree / name)
    for name in ("pom.xml", "mvnw"):
        shutil.copy2(ROOT / name, tree / name)
    for path in sorted(RESTORED):
        (tree / path).write_bytes(git("show", "HEAD:" + path))
    for path in sorted(ADDED):
        (tree / path).unlink()
    for path in sorted(TEST_HEAD):
        (tree / path).write_bytes(git("show", "HEAD:" + path))
    (tree / TEST_LEFT_OUT).unlink()


def soften(tree):
    """Run B: the four overlap tests assert softly (one SoftAssertions per test instance, checked after each test)."""
    path = tree / FOLLOWUP_IT
    source = path.read_text(encoding="utf-8")
    start = source.index("    @Test void T_10_25_twoOverlappingKeyedCompletions")
    end = source.index("    /** A token of `s10f-<id>`")
    region = source[start:end].replace("assertThat(", "soft.assertThat(")
    source = source[:start] + region + source[end:]
    helper = "findFirst().orElseThrow(); }\n    }"
    if source.count(helper) != 1:
        raise SystemExit("Overlap.with not found once")
    source = source.replace(helper, "findFirst().orElse(responses.getFirst()); }\n    }")
    collect = "            for (var answer : answers) { responses.add(answer.get(60, TimeUnit.SECONDS)); }\n"
    if source.count(collect) != 1:
        raise SystemExit("the answers' loop not found once")
    source = source.replace(collect, "            for (var answer : answers) {\n"
                            "                try { responses.add(answer.get(60, TimeUnit.SECONDS)); }\n"
                            "                catch (ExecutionException escaped) {\n"
                            "                    var uncaught = new org.springframework.mock.web.MockHttpServletResponse(); uncaught.setStatus(500);\n"
                            "                    uncaught.getOutputStream().write((\"{\\\"code\\\":\\\"UNCAUGHT \" + escaped.getCause().getClass().getSimpleName() + \"\\\"}\").getBytes());\n"
                            "                    responses.add(uncaught);\n"
                            "                }\n"
                            "            }\n")
    anchor = "    @BeforeEach void fixtures() {"
    source = source.replace(anchor, "    final org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();\n"
                            "    @org.junit.jupiter.api.AfterEach void softly() { soft.assertAll(); }\n\n" + anchor, 1)
    path.write_text(source, encoding="utf-8")


def run(tree, label, command):
    print(f"$ (cd {tree.relative_to(ROOT)} && " + " ".join(command) + ")", flush=True)
    result = subprocess.run(command, cwd=tree, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (ROOT / "target" / f"before-fix-e6t03-r5-{label}.out").write_text(result.stdout)
    print(f"exit {result.returncode}")
    for line in result.stdout.splitlines():
        if re.search(r"Tests run:.*in com|<<< (FAILURE|ERROR)", line):
            print(line[:300].rstrip())
    print(f"--- run {label}: per test (surefire + failsafe XML of the copy), with the failure message")
    for folder in ("surefire-reports", "failsafe-reports"):
        for report in sorted((tree / "target" / folder).glob("TEST-*.xml")):
            xml = report.read_text()
            for case in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', xml, re.S):
                body = case.group(4) or ""
                status = "FAILED" if "<failure" in body else "ERROR" if "<error" in body else "ok"
                print(f"{status:6} {case.group(2).split('.')[-1]}.{case.group(1)}")
                failure = re.search(r'<(failure|error) message="([^"]*)"', body)
                if failure:
                    message = failure.group(2).replace("&#10;", " ").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                    message = re.sub(r"\s+", " ", message)
                    parts = re.split(r"\s*-- failure \d+ --\s*", message)
                    if len(parts) == 1:
                        print("       " + message[:400])
                        continue
                    # A soft run lists every failed assertion: each with its line in the copy's test source.
                    print("       " + parts[0].strip())
                    source = (tree / FOLLOWUP_IT).read_text(encoding="utf-8").splitlines()
                    for part in parts[1:]:
                        line = re.search(r"\(FollowupIT\.java:(\d+)\)", part)
                        text = re.sub(r"\s*at FollowupIT\.\S+\(FollowupIT\.java:\d+\)\s*$", "", part.strip())
                        where = f"line {line.group(1)} `{source[int(line.group(1)) - 1].strip()[:120]}`" if line else "?"
                        print(f"       - {where}: {text[:300]}")


changed = set(git("diff", "--name-only", "HEAD", "--", "src/main").decode().split())
added = set(git("ls-files", "--others", "--exclude-standard", "--", "src/main").decode().split())
if changed != RESTORED or added != ADDED:
    raise SystemExit("unexpected production changes: " + ", ".join(sorted((changed ^ RESTORED) | (added ^ ADDED))))
print("restored HEAD (round 4) in the copies: " + ", ".join(sorted(p.rsplit("/", 1)[1] for p in RESTORED)))
print("removed from the copies (new in round 5): " + ", ".join(sorted(p.rsplit("/", 1)[1] for p in ADDED)))
print("HEAD test versions (they use the new API): " + ", ".join(sorted(p.rsplit("/", 1)[1] for p in TEST_HEAD))
      + "; left out: " + TEST_LEFT_OUT.rsplit("/", 1)[1])

copy_tree(TREE)
run(TREE, "A", ["./mvnw", "-q", "verify", "-Dtest=FollowupTransactionsTest,FollowupContractAccessTest", "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dit.test=FollowupIT,E6ContractIT", "-Dmaven.test.failure.ignore=true", "-Djacoco.skip=true"])
SOFT = ROOT / "target/before-fix-e6t03-r5-soft"
copy_tree(SOFT)
soften(SOFT)
run(SOFT, "B", ["./mvnw", "-q", "verify", "-Dtest=NoUnitTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=FollowupIT#" + OVERLAP_TESTS,
                "-Dfailsafe.failIfNoSpecifiedTests=false", "-Dmaven.test.failure.ignore=true", "-Djacoco.skip=true"])
