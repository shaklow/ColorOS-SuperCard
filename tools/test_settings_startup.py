"""Run real Activity/private-M3E startup checks on an Android test device.

Install the unified APK and matching :app:assembleDebugAndroidTest APK first,
or use --install-from for a CI artifact directory. Never clears data or credentials.
"""
import argparse
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', help='Required when several test devices are attached')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--install-from', type=Path, help='Install the two APKs from a CI artifact tree')
    parser.add_argument('--logcat-out', type=Path, help='Save test-device logcat even when startup fails')
    args = parser.parse_args()
    command = [args.adb] + (['-s', args.serial] if args.serial else [])
    try:
        if args.install_from:
            for pattern in ['SuperCard-*-unified.apk', 'app-debug-androidTest.apk']:
                apks = list(args.install_from.rglob(pattern))
                if len(apks) != 1:
                    raise SystemExit(f'Expected one {pattern}, found {len(apks)}')
                subprocess.run(command + ['install', '-r', str(apks[0])], check=True, timeout=args.timeout)
        # Raw mode retains INSTRUMENTATION_CODE even when the result has a stream
        # message. Pretty output prints only that message and hides the code.
        result = subprocess.run(command + ['shell', 'am', 'instrument', '-w', '-r',
            'dev.local.supercardhost.test/dev.local.supercardhost.SettingsStartupTest'],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=args.timeout)
        print(result.stdout, end='')
        if result.returncode or not re.search(r'INSTRUMENTATION_CODE:\s*-1\s*$', result.stdout) \
                or 'Settings startup: 4 entry points passed' not in result.stdout \
                or 'Floating cards: window embedding passed' not in result.stdout \
                or 'Floating lifecycle: detach/reattach passed' not in result.stdout \
                or 'Lens display: embedded activity and touch passed' not in result.stdout \
                or 'Root display: callback wrapper passed' not in result.stdout \
                or 'Card management: persistence, ordering and legacy entry passed' not in result.stdout \
                or 'Gesture settings: session, preview ownership and new page passed' not in result.stdout:
            raise SystemExit('Settings startup failed; inspect the instrumentation output and device crash log.')
    finally:
        if args.logcat_out:
            args.logcat_out.parent.mkdir(parents=True, exist_ok=True)
            with args.logcat_out.open('w', encoding='utf-8') as log:
                subprocess.run(command + ['logcat', '-d'], stdout=log, stderr=subprocess.STDOUT,
                               text=True, timeout=30)


if __name__ == '__main__':
    main()
