"""Build CCA Haiku's APK with the Android SDK command-line tools only (no Gradle, no Android Studio).

python android/haiku/build.py          -> build/android/CCA-Haiku-<version>.apk (signed)
python android/haiku/build.py --test   -> compile and run the plain-Java core test on this computer

Needs JDK 17 and the SDK at %ANDROID_HOME% or %LOCALAPPDATA%\\Android\\Sdk with build-tools 35.0.0 and
platforms;android-35. Work happens in an ASCII temporary folder because aapt2 does not accept every path.
The signing key is %USERPROFILE%\\.android\\cca-haiku.jks; it is created on first use, its password sits next
to it in cca-haiku.properties, and it never goes into the repository. Keep it: Android only installs an update
signed with the same key.
"""
import argparse
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
BUILD_TOOLS = "35.0.0"
PLATFORM = "android-35"
# Launcher icon sizes per density bucket.
ICONS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def sdk():
    base = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or
                Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
    tools, jar = base / "build-tools" / BUILD_TOOLS, base / "platforms" / PLATFORM / "android.jar"
    if not tools.is_dir() or not jar.is_file():
        sys.exit(f"Android SDK not found: need {tools} and {jar}")
    return tools, jar


def run(*args, **kw):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)
    if result.returncode:
        sys.exit(f"failed: {' '.join(map(str, args))}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def manifest_version():
    text = (HERE / "AndroidManifest.xml").read_text(encoding="utf-8")
    return re.search(r'android:versionName="([^"]+)"', text)[1], re.search(r'android:versionCode="(\d+)"', text)[1]


def keystore(work):
    home = Path(os.environ["USERPROFILE"]) / ".android"
    home.mkdir(exist_ok=True)
    store, props = home / "cca-haiku.jks", home / "cca-haiku.properties"
    if not store.exists():
        password = secrets.token_urlsafe(24)
        run("keytool", "-genkeypair", "-keystore", store, "-storepass", password, "-keypass", password, "-alias", "cca-haiku",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=MoriTeahouse CCA Haiku")
        props.write_text("password=" + password + "\n", encoding="utf-8")
    password = re.search(r"password=(\S+)", props.read_text(encoding="utf-8"))[1]
    return store, password


def icons(res):
    from PIL import Image  # the icon step is the only one that needs Pillow
    source = Image.open(ROOT / "assets" / "editions" / "haiku" / "icon-256.png").convert("RGBA")
    for bucket, px in ICONS.items():
        folder = res / f"mipmap-{bucket}"
        folder.mkdir(parents=True, exist_ok=True)
        source.resize((px, px), Image.LANCZOS).save(folder / "ic_launcher.png")


def sources(work, folder="src"):
    """Copies the Java sources into the ASCII work folder: javac on Windows rejects some non-ASCII paths."""
    target = work / folder
    shutil.copytree(HERE / folder, target)
    return sorted(target.rglob("*.java"))


def build():
    tools, jar = sdk()
    version, code = manifest_version()
    work = Path(tempfile.mkdtemp(prefix="cca-haiku-"))
    try:
        res = work / "res"
        shutil.copytree(HERE / "res", res)
        icons(res)
        assets = work / "assets" / "www"
        assets.mkdir(parents=True)
        for item in (HERE / "web").iterdir():
            if item.name != "mock-host.js":  # the desktop stand-in never ships
                shutil.copy2(item, assets / item.name)
        shutil.copy2(HERE / "AndroidManifest.xml", work / "AndroidManifest.xml")
        run(tools / "aapt2.exe", "compile", "--dir", res, "-o", work / "res.zip")
        run(tools / "aapt2.exe", "link", "-o", work / "base.apk", "-I", jar, "--manifest", work / "AndroidManifest.xml",
            "-A", work / "assets", work / "res.zip")
        classes = work / "classes"
        classes.mkdir()
        run("javac", "-source", "11", "-target", "11", "-encoding", "UTF-8", "-Xlint:-options", "-cp", jar, "-d", classes,
            *sources(work))
        dex = work / "dex"
        dex.mkdir()
        run(tools / "d8.bat", "--release", "--min-api", "29", "--lib", jar, "--output", dex, *sorted(classes.rglob("*.class")))
        with zipfile.ZipFile(work / "base.apk", "a", zipfile.ZIP_DEFLATED) as apk:
            apk.write(dex / "classes.dex", "classes.dex")
        run(tools / "zipalign.exe", "-f", "-p", "4", work / "base.apk", work / "aligned.apk")
        store, password = keystore(work)
        signed = work / "signed.apk"
        run(tools / "apksigner.bat", "sign", "--ks", store, "--ks-pass", "pass:" + password, "--ks-key-alias", "cca-haiku",
            "--out", signed, work / "aligned.apk")
        run(tools / "apksigner.bat", "verify", signed)
        badging = run(tools / "aapt2.exe", "dump", "badging", signed)
        out = ROOT / "build" / "android"
        out.mkdir(parents=True, exist_ok=True)
        final = out / f"CCA-Haiku-{version}.apk"
        shutil.copy2(signed, final)
        print(badging.splitlines()[0])
        print(f"{final}  {final.stat().st_size // 1024} KB  versionCode {code}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


def test():
    work = Path(tempfile.mkdtemp(prefix="cca-haiku-test-"))
    try:
        core = [f for f in sources(work) if f.parent.name == "core"]
        out = work / "classes"
        run("javac", "-encoding", "UTF-8", "-d", out, *core, *sources(work, "test"))
        result = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(out), "CoreTest"], capture_output=True,
                                text=True, encoding="utf-8", errors="replace")
        print(result.stdout + result.stderr, end="")
        return result.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    if args.test:
        sys.exit(test())
    build()


if __name__ == "__main__":
    main()
