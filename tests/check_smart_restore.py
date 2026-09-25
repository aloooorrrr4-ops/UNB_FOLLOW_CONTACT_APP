#!/usr/bin/env python3
"""Run the production pixel methods on the JVM without an Android emulator.

Only Bitmap/Rect/Color/JSON boundaries are substituted. This does not test Android
rendering, compositing or UI integration; assembleDebug remains a separate gate.
Requires Python 3 and Java 17 (including the jdk.compiler module).
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, default=ROOT / "app/src/main/java/com/unb/imageeditor/LocalEditorEngine.java")
parser.add_argument("--output", type=Path, help="Optional directory for before/after fixture PNGs")
parser.add_argument("--screenshot", type=Path, help="Optional local 709x1536 OCR-delete report screenshot; never committed")
args = parser.parse_args()
source = args.source.read_text()


def method(name):
    match = re.search(r"    private (?:static )?[\w\[\]]+ " + name + r"\(", source)
    if not match:
        raise RuntimeError(f"Production method missing: {name}")
    # Method endings in this class use four-space indentation; nested blocks
    # are indented further. No reimplementation of the pixel algorithm here.
    end = source.index("\n    }", match.end()) + len("\n    }")
    result = source[match.start():end]
    if name == "isTargetRelatedFringe":
        start = result.index("{") + 1
        result = result[:start] + "\n        recordFringe(x, y, width);" + result[start:]
    return result


methods = "\n".join(method(name) for name in (
    "colorMatchSelection", "hasRestoreDonors", "isTargetRelatedFringe", "liesOnTargetBackgroundBlend",
    "estimateMatchedBackgroundColor", "colorDistance", "blend", "parseColor", "clamp",
    "eraseRasterTextPreserveBackground", "medianTextBackground", "textBackgroundPalette", "textPaletteDistance", "estimateRegionBorderColor",
    "filterLikelyGlyphComponents", "ringAverageColor"))
template = (ROOT / "tests/SmartRestoreHarness.java.in").read_text()
with tempfile.TemporaryDirectory(prefix="smart-restore-") as folder:
    path = Path(folder) / "SmartRestoreHarness.java"
    path.write_text(template.replace("// PRODUCTION_METHODS", methods))
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)], check=True)
    command = ["java", "-Djava.awt.headless=true", "-cp", folder, "SmartRestoreHarness"]
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
        command.append(str(args.output.resolve()))
    if args.screenshot:
        if not args.output:
            parser.error("--screenshot requires --output")
        command.append(str(args.screenshot.resolve()))
    subprocess.run(command, check=True)
