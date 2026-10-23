"""Check legacy management aliases in the actual unified-manifest generator, offline."""
import importlib.util
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unified_build', ROOT / 'tools/unified-prototype/build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

def main():
    with tempfile.TemporaryDirectory(prefix='supercard-management-manifest-') as directory:
        output = Path(directory)
        build.manifest(output, 30, '0.2.23-unified')
        app = ET.parse(output / 'AndroidManifest.xml').getroot().find('application')
        names = [node.get(build.A + 'name') for node in app if node.tag in ('activity', 'activity-alias')]
        assert len(names) == len(set(names)), 'Duplicate activity/alias name'
        old = [node for node in app if node.get(build.A + 'name') == 'com.vivo.card.setting.CardSettingActivity']
        assert len(old) == 1 and old[0].tag == 'activity-alias'
        assert old[0].get(build.A + 'targetActivity') == 'dev.local.supercardhost.CardManagementActivity'
        assert old[0].get(build.A + 'exported') == 'true'
        assert 'com.vivo.card.setting.CardEditActivity' in names
        editor = [node for node in app if node.get(build.A + 'name') == 'com.vivo.card.setting.CardEditActivity']
        assert len(editor) == 1 and editor[0].tag == 'activity-alias'
        assert editor[0].get(build.A + 'targetActivity') == 'dev.local.supercardhost.CardFunctionSettingsActivity'
        assert editor[0].get(build.A + 'exported') == 'true'
        assert 'dev.local.supercardhost.HostActivity' in names
        assert app.find('activity[@' + build.A + 'name="dev.local.supercardhost.CardManagementActivity"]') is not None
        print('Card management: management and function editor alias checks passed.')

if __name__ == '__main__':
    main()
