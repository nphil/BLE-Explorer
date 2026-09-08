"""Package the tracked, non-secret project for a private source download."""
from pathlib import Path
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
output = root / 'dist/ble-studio-source.zip'
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for name in files:
        if not name or name.startswith('.openai/') or name in {'dist/ble-studio-source.zip','dist/README.md'}:
            continue
        archive.write(root / name, 'ble-studio/' + name)
with ZipFile(output) as archive:
    assert archive.testzip() is None
    assert 'ble-studio/README.md' in archive.namelist()
print('Validated source download:', output)
