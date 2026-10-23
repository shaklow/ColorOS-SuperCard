"""Run real Activity/private-M3E startup checks on an Android test device.

Install the unified APK and matching :app:assembleDebugAndroidTest APK first,
or use --install-from for a CI artifact directory. Never clears data or credentials.
"""
import argparse
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--secure-native', action='store_true', help='Require rooted test adbd for scoped secure-display permission overrides')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', help='Required when several test devices are attached')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--install-from', type=Path, help='Install the two APKs from a CI artifact tree')
    parser.add_argument('--logcat-out', type=Path, help='Save test-device logcat even when startup fails')
    args = parser.parse_args()
    command = [args.adb] + (['-s', args.serial] if args.serial else [])
    try:
        if args.secure_native:
            result = subprocess.run(command + ['root'], capture_output=True, text=True, timeout=30)
            if result.returncode or 'cannot run as root' in result.stdout + result.stderr:
                raise SystemExit('Secure native display regression requires a rooted test emulator.')
            for attempt in range(20):
                subprocess.run(command + ['wait-for-device'], check=True, timeout=30)
                uid = subprocess.run(command + ['shell', 'id', '-u'], capture_output=True, text=True, timeout=10)
                if uid.returncode == 0 and uid.stdout.strip() == '0':
                    break
                time.sleep(.25)
            else:
                raise SystemExit('Test adbd did not become root; secure regression was not run.')
        if args.install_from:
            for pattern in ['SuperCard-*-unified.apk', 'app-debug-androidTest.apk']:
                apks = list(args.install_from.rglob(pattern))
                if len(apks) != 1:
                    raise SystemExit(f'Expected one {pattern}, found {len(apks)}')
                subprocess.run(command + ['install', '-r', str(apks[0])], check=True, timeout=args.timeout)
        # Raw mode retains INSTRUMENTATION_CODE even when the result has a stream
        # message. Pretty output prints only that message and hides the code.
        instrumentation = command + ['shell', 'am', 'instrument', '-w', '-r']
        if args.secure_native:
            instrumentation += ['-e', 'secureNative', 'true']
        instrumentation += ['dev.local.supercardhost.test/dev.local.supercardhost.SettingsStartupTest']
        result = subprocess.run(instrumentation,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=args.timeout)
        print(result.stdout, end='')
        if result.returncode or not re.search(r'INSTRUMENTATION_CODE:\s*-1\s*$', result.stdout) \
                or 'Settings startup: 4 entry points passed' not in result.stdout \
                or 'Floating cards: window embedding passed' not in result.stdout \
                or 'Floating lifecycle: detach/reattach passed' not in result.stdout \
                or 'Lens display: embedded activity and touch passed' not in result.stdout \
                or 'Root display: callback wrapper passed' not in result.stdout \
                or 'Card management: persistence, ordering and legacy entry passed' not in result.stdout \
                or 'Gesture settings: session, preview ownership and new page passed' not in result.stdout \
                or 'Native core: secure window, switching and lock cleanup passed' not in result.stdout \
                or (args.secure_native and 'Secure native display: actual secure secondary display and touch passed' not in result.stdout):
            raise SystemExit('Settings startup failed; inspect the instrumentation output and device crash log.')
    finally:
        if args.logcat_out:
            args.logcat_out.parent.mkdir(parents=True, exist_ok=True)
            with args.logcat_out.open('w', encoding='utf-8') as log:
                subprocess.run(command + ['logcat', '-d'], stdout=log, stderr=subprocess.STDOUT,
                               text=True, timeout=30)


if __name__ == '__main__':
    main()
