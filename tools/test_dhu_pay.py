"""Offline DHU behavioral checks; fixture traffic never reaches the school server.

The only Android fake is Bitmap's pixel storage. The real HTTP session client,
HTML parser and QR/CODE128 encoders are compiled and executed without a device.
Pass --offline to require checksum-verified cached test dependencies.
"""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import uuid
from contextlib import contextmanager
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = {
    "core-3.5.3.jar": ("com/google/zxing/core/3.5.3", "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"),
    "json-20240303.jar": ("org/json/json/20240303", "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"),
}


@contextmanager
def work_directory(cache):
    # Python 3.12's Windows mkdtemp ACL can exclude a restricted process token.
    # Use a unique directory with the build workspace's inherited permissions.
    directory = cache / ("dhu-tests-" + uuid.uuid4().hex)
    directory.mkdir()
    try:
        yield directory
    finally:
        if not directory.resolve().is_relative_to(cache.resolve()):
            raise ValueError("Test output escaped its cache directory")
        shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    cache = ROOT / "tools/cache"
    cache.mkdir(exist_ok=True)
    jars = []
    for name, (remote, expected) in ARTIFACTS.items():
        target = cache / name
        if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            if args.offline:
                raise SystemExit(f"Missing verified test dependency: {name}")
            request = urllib.request.Request(f"https://repo.maven.apache.org/maven2/{remote}/{name}",
                                             headers={"User-Agent": "SuperCard-DHU-tests"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit(f"Test dependency checksum mismatch: {name}")
            target.write_bytes(data)
        jars.append(target)
    java_home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    java = Path(java_home) / "bin" / f"java{suffix}" if java_home else Path(shutil.which("java") or "java")
    javac = java.with_name(f"javac{suffix}")
    with work_directory(cache) as out:
        fake = out / "android/graphics/Bitmap.java"
        fake.parent.mkdir(parents=True)
        fake.write_text("""package android.graphics;
public class Bitmap {
 public enum Config { ARGB_8888 }
 public final int[] pixels; private final int width,height;
 private Bitmap(int[] p,int w,int h){pixels=p.clone();width=w;height=h;}
 public static Bitmap createBitmap(int[] p,int w,int h,Config c){return new Bitmap(p,w,h);}
 public int getWidth(){return width;} public int getHeight(){return height;}
}
""", encoding="utf-8")
        sources = ROOT / "app/src/main/java/dev/local/supercardhost"
        classpath = os.pathsep.join(map(str, jars))
        subprocess.run([str(javac), "-encoding", "UTF-8", "--release", "17", "-cp", classpath,
                        "-d", str(out), str(fake), *(str(sources / name) for name in
                        ("DhuPayProtocol.java", "DhuPayClient.java", "DhuPayCodes.java")),
                        str(ROOT / "tools/TestDhuPay.java")], check=True)
        subprocess.run([str(java), "-cp", str(out) + os.pathsep + classpath,
                        "dev.local.supercardhost.TestDhuPay"], check=True)


if __name__ == "__main__":
    main()
