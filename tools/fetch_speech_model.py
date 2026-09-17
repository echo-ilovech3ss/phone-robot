#!/usr/bin/env python3
"""Fetch the pinned Vosk model into APK assets using only the Python standard library."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

MODEL_NAME = "vosk-model-small-en-us-0.15"
MODEL_URL = f"https://alphacephei.com/vosk/models/{MODEL_NAME}.zip"
MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_EXTRACTED_BYTES = 300 * 1024 * 1024
MAX_ENTRIES = 500
CHUNK_BYTES = 1024 * 1024
REQUIRED_FILES = ("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")
DESTINATION = Path(__file__).resolve().parents[1] / "app/src/main/assets/model-en-us"


class HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise ValueError("Refusing a non-HTTPS model download redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(destination: Path) -> None:
    opener = urllib.request.build_opener(HttpsOnlyRedirect())
    request = urllib.request.Request(MODEL_URL, headers={"User-Agent": "PhoneRobot-model-setup/1"})
    deadline = time.monotonic() + 300
    total = 0
    with opener.open(request, timeout=30) as response, destination.open("wb") as output:
        while block := response.read1(CHUNK_BYTES):
            total += len(block)
            if total > MAX_ARCHIVE_BYTES or time.monotonic() > deadline:
                raise ValueError("Model download exceeded its size or time limit")
            output.write(block)


def verify_archive(archive: Path) -> None:
    digest = hashlib.sha256()
    total = 0
    with archive.open("rb") as source:
        while block := source.read(CHUNK_BYTES):
            total += len(block)
            if total > MAX_ARCHIVE_BYTES:
                raise ValueError("Model archive exceeds the size limit")
            digest.update(block)
    if digest.hexdigest() != MODEL_SHA256:
        raise ValueError("Model SHA-256 mismatch; refusing to extract unverified data")


def extract_archive(archive: Path, target: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries) > MAX_ENTRIES or sum(entry.file_size for entry in entries) > MAX_EXTRACTED_BYTES:
            raise ValueError("Model ZIP exceeds extraction limits")
        seen: set[PurePosixPath] = set()
        total = 0
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                raise ValueError("Unsafe path in model ZIP")
            if not path.parts or path.parts[0] != MODEL_NAME:
                raise ValueError("Unexpected top-level directory in model ZIP")
            mode = stat.S_IFMT(entry.external_attr >> 16)
            if mode not in (0, stat.S_IFREG, stat.S_IFDIR) or entry.flag_bits & 1:
                raise ValueError("Special file or encrypted entry in model ZIP")
            relative = PurePosixPath(*path.parts[1:])
            if relative in seen:
                raise ValueError("Duplicate path in model ZIP")
            seen.add(relative)
            output = target.joinpath(*relative.parts)
            if entry.is_dir():
                output.mkdir(parents=True, exist_ok=True)
                continue
            output.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with source.open(entry) as input_file, output.open("xb") as output_file:
                while block := input_file.read(CHUNK_BYTES):
                    total += len(block)
                    written += len(block)
                    if total > MAX_EXTRACTED_BYTES or written > entry.file_size:
                        raise ValueError("Model ZIP decompressed beyond its declared size")
                    output_file.write(block)
            if written != entry.file_size:
                raise ValueError("Truncated model ZIP member")
    if any(not (target / name).is_file() or (target / name).stat().st_size == 0 for name in REQUIRED_FILES):
        raise ValueError("Downloaded model lacks required recognition files")
    (target / "uuid").write_text(MODEL_SHA256 + "\n", encoding="ascii")


def install(archive: Path) -> None:
    verify_archive(archive)
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    if DESTINATION.is_symlink():
        raise ValueError("Refusing to replace a symlink at the model destination")
    if DESTINATION.exists() and not (DESTINATION / "uuid").is_file():
        raise ValueError("Model destination contains unmanaged files; move it aside before setup")
    with tempfile.TemporaryDirectory(prefix=".speech-model-", dir=DESTINATION.parent) as temporary:
        staging = Path(temporary) / "new"
        staging.mkdir()
        extract_archive(archive, staging)
        previous = DESTINATION.with_name(".model-en-us.previous")
        if previous.exists():
            raise ValueError(f"Previous interrupted install preserved at {previous}; move it aside before setup")
        try:
            if DESTINATION.exists():
                os.replace(DESTINATION, previous)
            os.replace(staging, DESTINATION)
        except BaseException:
            if previous.exists() and not DESTINATION.exists():
                os.replace(previous, DESTINATION)
            raise
        if previous.exists():
            shutil.rmtree(previous)
    size = sum(path.stat().st_size for path in DESTINATION.rglob("*") if path.is_file())
    print(f"Installed {MODEL_NAME}: {size:,} bytes into {DESTINATION}")
    print(f"Verified archive SHA-256: {MODEL_SHA256}")
    print("The next APK build bundles this model; recognition needs no runtime download.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already-downloaded ZIP, still enforcing the pinned SHA-256")
    args = parser.parse_args()
    try:
        if args.archive:
            install(args.archive)
        else:
            with tempfile.TemporaryDirectory(prefix="phone-robot-model-") as temporary:
                archive = Path(temporary) / "model.zip"
                print(f"Downloading {MODEL_URL}")
                download(archive)
                install(archive)
    except (OSError, ValueError, zipfile.BadZipFile, urllib.error.URLError) as error:
        print(f"Speech model setup failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
