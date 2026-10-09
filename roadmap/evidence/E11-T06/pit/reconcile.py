#!/usr/bin/env python3
"""Build a per-batch survivor triage table from a PIT batch and earlier pre-triage tables.

Usage: reconcile.py BATCH_TSV BATCH_XML OUTPUT_MD TABLE.md [TABLE.md ...]

Each row of BATCH_TSV (status, file, class, method, line, mutator, description) gets the
Decision of the single pre-triage row with the same simple class, method, line and mutator
short name; a pre-triage row that names an `idx` must also match one of the XML mutation's
indexes. No match or several matches -> Decision `UNMATCHED` (listed on stdout).
Stdlib only; reads the inputs and writes OUTPUT_MD, nothing else.
"""
import csv
import os
import re
import sys
import xml.etree.ElementTree as ET

STATUSES = ("SURVIVED", "NO_COVERAGE", "TIMED_OUT", "KILLED", "MEMORY_ERROR",
            "RUN_ERROR", "NON_VIABLE", "NOT_STARTED", "STARTED")


def short_mutator(name):
    """`...returns.NullReturnValsMutator` / `NullReturnValsMutator` -> `NullReturnVals`."""
    return name.strip().rsplit(".", 1)[-1].replace("Mutator", "")


def simple_class(name):
    """`com.x.WeekAgendaPdf$Pages` / `api.MeController` -> last dotted segment."""
    return name.strip().strip("`").rsplit(".", 1)[-1]


def first_int(text):
    match = re.search(r"\d+", text)
    return int(match.group()) if match else None


def read_tsv(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))


def read_xml(path):
    rows = []
    for mutation in ET.parse(path).getroot().iter("mutation"):
        def text(tag):
            node = mutation.find(tag)
            return (node.text or "").strip() if node is not None else ""
        rows.append({
            "status": mutation.get("status", ""),
            "key": (text("mutatedClass"), text("mutatedMethod"), text("lineNumber"),
                    short_mutator(text("mutator")), text("description")),
            "indexes": [int(i.text) for i in mutation.findall("indexes/index") if i.text],
        })
    return rows


def assign_indexes(tsv_rows, xml_rows):
    """For each TSV row, the index list of the next unused XML mutation with the same key."""
    used = set()
    result = []
    for row in tsv_rows:
        key = (row["class"], row["method"], row["line"], short_mutator(row["mutator"]),
               row["description"])
        found = None
        for position, mutation in enumerate(xml_rows):
            if position in used or mutation["key"] != key:
                continue
            if mutation["status"] == row["status"]:
                found = position
                break
            if found is None:
                found = position
        if found is None:
            result.append(None)
        else:
            used.add(found)
            result.append(xml_rows[found]["indexes"])
    return result


def split_row(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def parse_mutator_cell(cell):
    """Return (short name, idx or None) from the variants used by the pre-triage tables:
    `SURVIVED Math: ... (idx 29)`, `NegateConditionals idx 79 (NO_COVERAGE): ...`,
    `NegateConditionals (SURVIVED, idx 78): ...`, `VoidMethodCall (NO_COVERAGE): ...`."""
    words = cell.split()
    while words and words[0].strip("(),:") in STATUSES:
        words.pop(0)
    rest = " ".join(words)
    name = re.match(r"[A-Za-z_]+", rest)
    name = short_mutator(name.group()) if name else ""
    head = rest.split(":", 1)[0]
    idx = re.search(r"\bidx (\d+)", head)
    if not idx:
        idx = re.search(r"\(idx (\d+)\)\s*$", rest)
    return name, int(idx.group(1)) if idx else None


def read_tables(paths):
    entries = []
    for path in paths:
        columns = None
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if not line.lstrip().startswith("|"):
                    columns = None
                    continue
                cells = split_row(line)
                if columns is None:
                    lowered = [c.lower() for c in cells]
                    names = {"class": None, "method": None, "line": None, "mutator": None,
                             "decision": None}
                    for i, header in enumerate(lowered):
                        for name in names:
                            if names[name] is None and header.startswith(name):
                                names[name] = i
                    if all(v is not None for v in names.values()):
                        columns = names
                    continue
                if set("".join(cells)) <= set("-: "):
                    continue
                if len(cells) <= max(columns.values()):
                    continue
                mutator, idx = parse_mutator_cell(cells[columns["mutator"]])
                entries.append({
                    "source": f"{os.path.basename(path)}:{number}",
                    "class": simple_class(cells[columns["class"]]),
                    "method": cells[columns["method"]].split("(", 1)[0].strip().strip("`"),
                    "line": first_int(cells[columns["line"]]),
                    "mutator": mutator,
                    "idx": idx,
                    "decision": cells[columns["decision"]],
                })
    return entries


def main(argv):
    if len(argv) < 5:
        sys.exit(__doc__.strip().splitlines()[2])
    tsv_path, xml_path, out_path, tables = argv[1], argv[2], argv[3], argv[4:]
    tsv_rows = read_tsv(tsv_path)
    indexes = assign_indexes(tsv_rows, read_xml(xml_path))
    entries = read_tables(tables)
    batch = re.match(r"batch(\w+?)-not-killed", os.path.basename(tsv_path))
    batch = batch.group(1) if batch else os.path.basename(tsv_path)

    out = [
        f"# E11-T06 · PIT batch {batch} survivor triage",
        "",
        f"Sources: `{os.path.basename(tsv_path)}` / `{os.path.basename(xml_path)}`; decisions copied by "
        f"`reconcile.py` from {', '.join('`' + os.path.basename(t) + '`' for t in tables)} "
        "(match on class, method, line, mutator and, where the pre-triage names one, the XML index).",
        "",
        "| Class:line | Method | Mutator | Status | Decision |",
        "|---|---|---|---|---|",
    ]
    counts = {"test": 0, "reason": 0, "UNMATCHED": 0}
    unmatched = []
    used = set()
    for row, row_indexes in zip(tsv_rows, indexes):
        cls, method, line = simple_class(row["class"]), row["method"], int(row["line"])
        mutator = short_mutator(row["mutator"])
        matches = [
            e for e in entries
            if e["class"] == cls and e["method"] == method and e["line"] == line
            and e["mutator"] == mutator
            and (e["idx"] is None or (row_indexes is not None and e["idx"] in row_indexes))
        ]
        label = mutator
        if row_indexes:
            label += " idx " + "/".join(str(i) for i in row_indexes)
        if len(matches) == 1:
            decision = matches[0]["decision"]
            used.add(matches[0]["source"])
            # `test: …` / `test WS-x` count as test; `reason: …` and bare reasons
            # (`truly equivalent — …`, `unreachable with real data — …`) as reason.
            counts["test" if re.match(r"test\b", decision) else "reason"] += 1
        else:
            decision = "UNMATCHED"
            counts["UNMATCHED"] += 1
            why = f"{len(matches)} candidates: " + ", ".join(m["source"] for m in matches) if matches else "no candidate"
            unmatched.append(f"  {cls}:{line} {method} {label} {row['status']} ({why})")
        if row_indexes is None:
            print(f"warning: no XML mutation for {cls}:{line} {method} {mutator}", file=sys.stderr)
        out.append(f"| {cls}:{line} | {method} | {label} | {row['status']} | {decision} |")

    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")
    print(f"rows {len(tsv_rows)}: matched test {counts['test']} / matched reason {counts['reason']}"
          f" / UNMATCHED {counts['UNMATCHED']}")
    print(f"pre-triage rows read {len(entries)}, used {len(used)}")
    if unmatched:
        print("UNMATCHED:")
        print("\n".join(unmatched))


if __name__ == "__main__":
    main(sys.argv)
