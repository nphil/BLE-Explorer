"""Build the HACS zip with no project-level directory prefix."""

from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def package(root: Path, output: Path) -> None:
    component = root / "custom_components" / "ble_command_explorer"
    manifest = component / "manifest.json"
    if not manifest.is_file():
        raise SystemExit(f"missing component manifest: {manifest}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", ZIP_DEFLATED) as archive:
        for path in sorted(component.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                archive.write(
                    path,
                    Path("custom_components/ble_command_explorer")
                    / path.relative_to(component),
                )
    with ZipFile(output) as archive:
        names = set(archive.namelist())
    expected = "custom_components/ble_command_explorer/manifest.json"
    # Manual installation archive: extract into /config. HACS installs the
    # source component folder; zip_release is intentionally not enabled.
    if expected not in names:
        raise SystemExit(f"package did not contain {expected} at archive root")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).parents[1])
    parser.add_argument("--output", type=Path, default=Path("dist/ble_command_explorer.zip"))
    args = parser.parse_args()
    package(args.root.resolve(), args.output)
