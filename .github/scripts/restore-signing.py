"""Restore only the two expected signing files from the AES-encrypted ZIP."""
import os
from pathlib import Path
import sys
import pyzipper

archive, destination = map(Path, sys.argv[1:3])
password = os.environ.get("SIGNING_PROPERTIES_ZIP_PASSWORD", "")
if not password:
    raise SystemExit("SIGNING_PROPERTIES_ZIP_PASSWORD is not configured")
expected = {"release.keystore", "signing.properties"}
os.umask(0o077)
destination.mkdir(parents=True, exist_ok=True)
with pyzipper.AESZipFile(archive) as bundle:
    bundle.setpassword(password.encode())
    if set(bundle.namelist()) != expected or len(bundle.infolist()) != 2:
        raise SystemExit("Unexpected signing archive entries")
    for entry in bundle.infolist():
        if not entry.flag_bits & 1 or entry.file_size > 1024 * 1024:
            raise SystemExit("Invalid signing archive entry")
        data = bundle.read(entry.filename)
        if not data:
            raise SystemExit("Empty signing archive entry")
        with (destination / entry.filename).open("xb") as output:
            output.write(data)
print("Signing archive restored")
