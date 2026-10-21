#!/usr/bin/env python3
"""E11-T17: the totals of target/surefire-reports and target/failsafe-reports, and one line per class this task adds or changes.

Usage: python3 -I summary.py <title> <class>...  (class names as in the report files, without the com.agilityhub.core. prefix)
"""
import glob
import sys
import xml.etree.ElementTree as ElementTree

PREFIX = "com.agilityhub.core."


def suites(directory):
    result = {}
    for path in sorted(glob.glob(f"target/{directory}/TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        result[root.get("name")] = tuple(int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped"))
    return result


def line(counts):
    return "tests {} · failures {} · errors {} · skipped {}".format(*counts)


def main(title, classes):
    print(title)
    for directory in ("surefire-reports", "failsafe-reports"):
        found = suites(directory)
        totals = tuple(sum(values[i] for values in found.values()) for i in range(4))
        print(f"\n{directory.split('-')[0]}: {len(found)} classes · {line(totals)}")
        for name in classes:
            if PREFIX + name in found:
                print(f"  {name}: {line(found[PREFIX + name])}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
