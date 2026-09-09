"""Package the tracked, non-secret project for a private source download."""
from pathlib import Path
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
output = root / 'dist/blueshark-source.zip'
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for name in files:
        if not name or name.startswith('.openai/') or name in {'dist/blueshark-source.zip'}:
            continue
        archive.write(root / name, 'blueshark/' + name)
with ZipFile(output) as archive:
    assert archive.testzip() is None
    assert 'blueshark/README.md' in archive.namelist()
print('Validated source download:', output)
