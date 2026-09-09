#!/usr/bin/env python3
"""One-off script (not part of the app's runtime build) that builds Navic's bundled
AutoEq device-preset search index.

AutoEq (jaakkopasanen/AutoEq on GitHub, MIT licensed) publishes one "GraphicEQ.txt"
correction-curve file per measured device, at:

    results/<measurement-source>/<rig>/<device name>/<device name> GraphicEQ.txt

Bundling all of those raw files (8,830 of them as of this run) isn't worth the app's
size budget. Instead we bundle a lightweight index -- just {deviceName,
measurementSource, path} per entry, generated here -- and fetch the actual curve
text on demand from raw.githubusercontent.com when the user picks a device (see
AutoEqRepository.kt).

Usage:
    python3 generate_autoeq_index.py <path-to-file-list> <output-json-path>

<path-to-file-list> is a newline-separated list of repo-relative paths to every
"GraphicEQ.txt" file, one per line, e.g. produced by:

    git clone --filter=blob:none --depth 1 --no-checkout \\
        https://github.com/jaakkopasanen/AutoEq.git /tmp/autoeq-tree
    git -C /tmp/autoeq-tree ls-tree -r --name-only HEAD | grep 'GraphicEQ.txt$' \\
        > /tmp/all_geq_paths.txt

A blobless clone (or the `gh api repos/.../git/trees/master?recursive=1` call
described in the task, when `gh` is available) is preferred over the GitHub REST
"get tree" API called directly with curl: with 8,830+ matching files across ~52k
total repo entries, that API's recursive response is large enough to trip GitHub's
truncation limit, which would silently drop entries near the end of the tree
without any obvious error. `git ls-tree` on a blobless clone has no such limit --
it reads the actual complete git tree.
"""
import json
import sys


def parse_entry(path: str) -> dict | None:
	parts = path.split("/")
	# results/<measurement-source>/<rig>/<device name>/<device name> GraphicEQ.txt
	if len(parts) != 5 or parts[0] != "results":
		return None
	measurement_source, _rig, device_dir, filename = parts[1], parts[2], parts[3], parts[4]
	suffix = " GraphicEQ.txt"
	if not filename.endswith(suffix):
		return None
	# Device name is the directory name, which the filename prefix always matches
	# (verified directly against the full file list before writing this script --
	# 0 mismatches across all 8,830 entries).
	device_name = device_dir
	return {
		"deviceName": device_name,
		"measurementSource": measurement_source,
		"path": path,
	}


def main() -> None:
	if len(sys.argv) != 3:
		print(f"usage: {sys.argv[0]} <path-to-file-list> <output-json-path>", file=sys.stderr)
		sys.exit(1)

	list_path, output_path = sys.argv[1], sys.argv[2]

	entries = []
	skipped = 0
	with open(list_path, encoding="utf-8") as f:
		for line in f:
			line = line.rstrip("\n")
			if not line:
				continue
			entry = parse_entry(line)
			if entry is None:
				skipped += 1
				continue
			entries.append(entry)

	entries.sort(key=lambda e: (e["deviceName"].lower(), e["measurementSource"]))

	with open(output_path, "w", encoding="utf-8") as f:
		json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))

	print(f"wrote {len(entries)} entries to {output_path} (skipped {skipped} unparseable lines)")


if __name__ == "__main__":
	main()
