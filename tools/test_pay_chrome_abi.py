"""Verify the appearance bridge's signatures against real bundled DEX; never executes vendor code."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + suffix) if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
    javac = java.with_name('javac' + suffix)
    compiler = [str(javac)] if javac.exists() else [str(java), 'com.sun.tools.javac.Main']
    classpath = os.pathsep.join(str(ROOT / 'tools/asset-patcher-libs' / name) for name in ['dexlib2.jar', 'guava.jar', 'jsr305.jar'])
    with tempfile.TemporaryDirectory(prefix='pay-appearance-abi-') as directory:
        subprocess.run(compiler + ['-encoding', 'UTF-8', '--release', '17', '-cp', classpath, '-d', directory, str(ROOT / 'tools/TestPayChromeAbi.java')], check=True)
        subprocess.run([str(java), '-cp', directory + os.pathsep + classpath, 'TestPayChromeAbi', str(ROOT / 'app/src/main/assets/supercard-code.zip')], check=True)


if __name__ == '__main__':
    main()
