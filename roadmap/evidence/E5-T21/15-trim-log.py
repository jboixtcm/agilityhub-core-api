import difflib
import pathlib
import sys

# E5-T21: the before-fix rerun's raw log is 8.4 MB, because OpenApiSnapshotTest's failure prints the whole OpenAPI contract
# twice (expected and actual), once in the test output and once in Maven's Results, and MockMvc prints it on one line
# (`Body = {...}`). This keeps every other line as it is, replaces each contract dump with a one-line marker, prints the
# unified diff of each expected/actual pair, and cuts any line over 4000 characters to its first 200.
LONG = 4000
raw = [line if len(line) <= LONG else line[:200] + " [E5-T21 trim: %d more characters elided]" % (len(line) - 200)
       for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
out, dumps, i = [], [], 0
while i < len(raw):
    line = raw[i]
    if line.strip() in ("expected:", "but was:") and i + 1 < len(raw) and raw[i + 1].startswith('  "{'):
        j = i + 1
        while j < len(raw) and not (raw[j].startswith("\tat ") or raw[j].startswith("[ERROR]")
                                    or raw[j].startswith("[INFO]") or raw[j].strip() == "but was:"):
            j += 1
        body = raw[i + 1:j]
        dumps.append(body)
        out.append(line)
        out.append("  [E5-T21 trim: %d lines of the OpenAPI contract elided]" % len(body))
        if line.strip() == "but was:" and len(dumps) >= 2:
            out.append("  [E5-T21 trim: the difference between the two, as a unified diff:]")
            out.extend("  " + d for d in difflib.unified_diff(
                dumps[-2], dumps[-1], "expected (docs/openapi/openapi.json at HEAD)", "but was (the live contract)", n=1, lineterm=""))
        i = j
        continue
    out.append(line)
    i += 1
print("\n".join(out))
