#!/usr/bin/env python3
"""Fetch a pinned MobileFaceNet model after acknowledging unresolved weight licensing."""

import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

REVISION = "dbf9732725cf1ab6e06b412651bb4858b5dfe0df"
URL = (
    "https://raw.githubusercontent.com/estebanuri/face_recognition/"
    f"{REVISION}/android/app/src/main/assets/mobile_face_net.tflite"
)
SHA256 = "b67366e085ec9f6c2afb05c10397a46edeb823367abaec77f64f5ce946ac2847"
SIZE = 5_243_108
DESTINATION = Path(__file__).resolve().parents[1] / "app/src/main/assets/mobile_face_net.tflite"


def fetch() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--acknowledge-model-license",
        action="store_true",
        help="Acknowledge that upstream does not establish redistribution/commercial rights for the weights.",
    )
    args = parser.parse_args()
    if not args.acknowledge_model_license:
        parser.error("Read NOTICE.face-model first, then pass --acknowledge-model-license for local prototype use.")
    if DESTINATION.exists() and hashlib.sha256(DESTINATION.read_bytes()).hexdigest() == SHA256:
        print(f"Verified existing model: {DESTINATION}")
        return
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        digest = hashlib.sha256()
        count = 0
        request = urllib.request.Request(URL, headers={"User-Agent": "PhoneRobot-model-setup/1"})
        with urllib.request.urlopen(request, timeout=60) as response:
            if not response.url.startswith("https://"):
                raise ValueError("Refusing a non-HTTPS download")
            with tempfile.NamedTemporaryFile(dir=DESTINATION.parent, prefix=".face-model-", delete=False) as output:
                temporary = Path(output.name)
                while chunk := response.read(64 * 1024):
                    count += len(chunk)
                    if count > SIZE:
                        raise ValueError("Model exceeds pinned size")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
        if count != SIZE or digest.hexdigest() != SHA256:
            raise ValueError("Model size or SHA256 does not match the pinned artifact")
        os.replace(temporary, DESTINATION)
        temporary = None
        print(f"Downloaded and verified {DESTINATION} ({SHA256})")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    fetch()
