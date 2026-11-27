#!/usr/bin/env python3
"""Stage the GitHub Pages site: the static site files plus the newest preview APKs.

Pages only ever serves one deployment, so every deploy (preview build or a plain
site update) must re-include the preview builds that should stay online. Older
builds are pulled back from the live site, using the list of successful
preview.yml runs to know which build numbers exist.

Layout produced:
    <out>/index.html, icon.png, site.webmanifest, .nojekyll
    <out>/previews/r<run>/Kodex-<abi>-preview-r<run>.apk
    <out>/previews/r<run>/SHA256SUMS       (read by the app to verify the download)
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Matches pickAsset() / checkPreviewRun() in core/update/KodexUpdater.kt.
ABIS = ("arm64-v8a", "armeabi-v7a", "universal")
SITE_FILES = ("index.html", "icon.png", "site.webmanifest")
SUMS = "SHA256SUMS"


def apk_names(run: int):
    return [f"Kodex-{a}-preview-r{run}.apk" for a in ABIS]


def http_get(url: str, token: str | None = None, retries: int = 4):
    headers = {"User-Agent": "kodex-pages-stager"}
    if token and "api.github.com" in url:
        headers["Authorization"] = f"Bearer {token}"
        headers["Accept"] = "application/vnd.github+json"
    last = None
    for attempt in range(retries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            last = e
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"GET {url} failed: {last}")


def successful_runs(repo: str, token: str | None):
    url = f"https://api.github.com/repos/{repo}/actions/workflows/preview.yml/runs?status=success&per_page=30"
    try:
        with http_get(url, token) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError:  # workflow file not on the default branch yet
        return []
    return sorted({r["run_number"] for r in data.get("workflow_runs", [])}, reverse=True)


def dir_size(path: Path) -> int:
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())


def write_sums(folder: Path) -> None:
    """SHA256SUMS in `sha256sum` format for every APK in [folder]."""
    lines = []
    for apk in sorted(folder.glob("*.apk")):
        h = hashlib.sha256()
        with open(apk, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        lines.append(f"{h.hexdigest()}  {apk.name}")
    (folder / SUMS).write_text("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--site-src", default="fastlane/metadata/android/preview-site")
    ap.add_argument("--site-url", required=True, help="live Pages base URL, no trailing slash")
    ap.add_argument("--repo", required=True)
    ap.add_argument("--keep", type=int, default=3, help="how many preview builds stay online")
    ap.add_argument("--new-run", type=int, help="run number of the build being published now")
    ap.add_argument("--new-apks", help="directory holding the freshly built APKs")
    ap.add_argument("--max-mb", type=int, default=900, help="Pages is limited to 1 GB; prune oldest above this")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    out = Path(args.out)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    for name in SITE_FILES:
        shutil.copy(Path(args.site_src) / name, out / name)
    (out / ".nojekyll").touch()

    previews = out / "previews"
    kept = 0
    if args.new_run and args.new_apks:
        dest = previews / f"r{args.new_run}"
        dest.mkdir(parents=True)
        for apk in sorted(Path(args.new_apks).glob("*.apk")):
            shutil.copy(apk, dest / apk.name)
        if not any(dest.iterdir()):
            print("::error::no APKs to publish", file=sys.stderr)
            return 1
        write_sums(dest)
        kept = 1
        print(f"new preview r{args.new_run}: {len(list(dest.glob('*.apk')))} APKs")

    # Carry the older builds over from the live site. Any API/network failure is
    # fatal on purpose: deploying without them would wipe them from the site.
    for run in successful_runs(args.repo, token):
        if kept >= args.keep:
            break
        if run == args.new_run:
            continue
        dest = previews / f"r{run}"
        got = 0
        for name in apk_names(run):
            try:
                with http_get(f"{args.site_url}/previews/r{run}/{name}") as resp:
                    dest.mkdir(parents=True, exist_ok=True)
                    with open(dest / name, "wb") as fh:
                        shutil.copyfileobj(resp, fh)
                got += 1
            except urllib.error.HTTPError:
                pass  # not on the site (pruned or never built)
        if got:
            write_sums(dest)
            kept += 1
            print(f"carried preview r{run}: {got} APKs")

    # Stay under the Pages size limit: drop the oldest builds (never the newest).
    while previews.exists():
        dirs = sorted(previews.iterdir(), key=lambda p: int(p.name[1:]))
        if len(dirs) <= 1 or sum(dir_size(d) for d in dirs) <= args.max_mb * 1024 * 1024:
            break
        print(f"size cap: dropping {dirs[0].name}")
        shutil.rmtree(dirs[0])

    total = dir_size(out) / (1024 * 1024)
    print(f"staged site: {total:.0f} MB, previews: {sorted(p.name for p in previews.iterdir()) if previews.exists() else []}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
