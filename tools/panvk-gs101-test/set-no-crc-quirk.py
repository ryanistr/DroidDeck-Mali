#!/usr/bin/env python3
"""Set the no_crc quirk the gs101 patch reads, which that patch never sets.

Patch 0003 (panfrost: disable transaction elimination on Mali-G78) adds `bool no_crc` to
pan_model.h's quirks and returns early from panfrost_is_2d() when it is true, but nothing in the
patch set ever sets it - so on its own the change is inert. This sets it on the Valhall arch-9 rows
(G77 0x9000 and G78 0x9222) so the "gsl+fix" arm tests the mechanism rather than the typo.

Argument: path to pan_model.c, edited in place.
"""
import re
import sys

HDR = re.compile(r'\s*VALHALL_MODEL\(PAN_PROD_ID\(9, (?:0, 0|2, 2)\),')
QUIRK = "MODEL_QUIRKS(.no_crc = true)),"


def main(path):
    lines = open(path).read().splitlines(keepends=True)
    out, i, hits = [], 0, 0
    while i < len(lines):
        if not HDR.match(lines[i]):
            out.append(lines[i]); i += 1; continue
        out.append(lines[i])
        nxt = lines[i + 1]
        indent = re.match(r'\s*', nxt).group(0)
        body = nxt.rstrip('\n')
        # The row's second line ends "))," which closes MODEL_RATES_X and VALHALL_MODEL both.
        # Drop the final ")," so VALHALL_MODEL stays open for the vararg below.
        if body.endswith(')),') and 'MODEL_QUIRKS' not in body:
            out.append(body[:-2] + ',\n')
            out.append(indent + QUIRK + '\n')
            hits += 1
        else:
            out.append(nxt)
        i += 2
    open(path, "w").write("".join(out))
    s = open(path).read()
    if s.count("(") != s.count(")"):
        sys.exit("unbalanced parens after edit: %d vs %d" % (s.count("("), s.count(")")))
    print("rows patched:", hits)


if __name__ == "__main__":
    main(sys.argv[1])
