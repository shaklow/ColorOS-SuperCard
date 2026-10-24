"""Validate the embedded M3E archive before making a unified installation APK."""
import io
from pathlib import Path
import sys
import zipfile
import struct


def defined_classes(dex):
    """Read definitions, not references: compile-only hidden framework ABIs must never ship."""
    def word(offset):
        return struct.unpack_from('<I', dex, offset)[0]
    strings, types, count, classes = word(60), word(68), word(96), word(100)
    for index in range(count):
        name_index = word(types + 4 * word(classes + 32 * index))
        offset = word(strings + 4 * name_index)
        while dex[offset] & 128:
            offset += 1
        offset += 1
        end = dex.index(0, offset)
        yield dex[offset:end].decode('utf-8')


def verify_archive(host_apk):
    with zipfile.ZipFile(host_apk) as host:
        archive = host.read('assets/dhu-ui.apk')
        for name in host.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                dex = host.read(name)
                if b'Lcom/google/android/material/button/MaterialButton;' in dex:
                    raise ValueError('Material Components leaked into the host DEX graph')
                forbidden = {'Landroid/view/InputChannel;', 'Landroid/view/InputEventReceiver;'}
                if forbidden.intersection(defined_classes(dex)):
                    raise ValueError('Compile-only platform input classes leaked into the APK')
    with zipfile.ZipFile(io.BytesIO(archive)) as ui:
        names = ui.namelist()
        if not {'AndroidManifest.xml', 'resources.arsc', 'classes.dex'}.issubset(names):
            raise ValueError('Incomplete embedded M3E archive')
        if any(n.startswith('lib/') and n.endswith('.so') for n in names):
            raise ValueError('M3E archive needs an explicit native library loader')
        dex = b''.join(ui.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
        for descriptor in [b'Ldev/local/supercardui/DhuCardSurface;',
                           b'Ldev/local/supercardui/SettingsSurface;',
                           b'Ldev/local/supercardui/LensCardSurface;',
                           b'Ldev/local/supercardui/PayCardAppearance;',
                           b'Ldev/local/supercardui/PayIdleArtwork;',
                           b'Ldev/local/supercardui/CardDesign;',
                           b'Ldev/local/supercardui/NativeCardSurface;',
                           b'Ldev/local/supercardui/CouponCardSurface;',
                           b'Ldev/local/supercardui/CardIconButton;',
                           b'Lcom/google/android/material/button/MaterialButton;',
                           b'Lcom/google/android/material/appbar/MaterialToolbar;',
                           b'Lcom/google/android/material/materialswitch/MaterialSwitch;',
                           b'Lcom/google/android/material/textfield/TextInputLayout;',
                           b'Lcom/google/android/material/loadingindicator/LoadingIndicator;',
                           b'Lcom/google/android/material/progressindicator/LinearProgressIndicator;']:
            if descriptor not in dex:
                raise ValueError('Missing embedded UI class: ' + descriptor.decode())
    print('Embedded M3E archive and host dependency isolation verified.')


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    verify_archive(Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'app/build/outputs/apk/debug/app-debug.apk')
