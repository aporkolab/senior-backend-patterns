#!/usr/bin/env python3
"""Create one private database credential per local lab, without logging it."""

import os
from pathlib import Path
import re
import secrets
import sys


def ensure_password(path):
    if path.is_symlink():
        raise ValueError("The lab environment file must not be a symlink")
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        contents = path.read_text()
        match = re.fullmatch(r"LAB_DB_PASSWORD=([0-9a-f]{64})\n?", contents)
        if not match:
            raise ValueError("The existing lab environment file is invalid; restore it before rebuilding")
        path.chmod(0o600)
        return match.group(1)
    password = secrets.token_hex(32)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(f"LAB_DB_PASSWORD={password}\n")
    return password


def main():
    try:
        password = ensure_password(Path(__file__).resolve().parent / ".env")
    except (OSError, ValueError) as error:
        print(f"Lab initialization failed: {error}", file=sys.stderr)
        return 1
    if os.environ.get("GITHUB_ACTIONS") == "true":
        # The devcontainer CLI logs resolved Compose values. Mask before it runs.
        print(f"::add-mask::{password}")
    print("Local lab database credential is ready.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
