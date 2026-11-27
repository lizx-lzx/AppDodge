#!/usr/bin/env python3
"""Build a debug APK using only the locally installed JDK 17 and Android SDK 35.

The pinned Shizuku and local pairing client libraries are vendored. No Gradle download or remote service is required.
Set ANDROID_SDK_ROOT and JAVA_HOME when the tools are installed elsewhere.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
if not (JAVA / 'bin/javac').exists():
    JAVA = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
BT = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'
ENV = dict(os.environ, JAVA_HOME=str(JAVA), PATH=str(JAVA / 'bin') + os.pathsep + os.environ.get('PATH', ''))

def run(*args):
    subprocess.run([str(x) for x in args], check=True, env=ENV, cwd=ROOT)

def javac(sources, dest, extra=None, libs=()):
    dest.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(map(str, [ANDROID, *([extra] if extra else []), *libs]))
    args = [JAVA/'bin/javac', '-encoding', 'UTF-8', '--release', '8', '-classpath', classpath, '-d', dest]
    run(*args, *sources)

def apk(name, manifest, sources, resources=None, extra=None, libs=(), native=None):
    work = ROOT/'build'/name
    if work.exists(): shutil.rmtree(work)
    (work/'generated').mkdir(parents=True)
    link = [BT/'aapt2', 'link', '-o', work/'resources.apk', '-I', ANDROID, '--manifest', manifest,
            '--java', work/'generated', '--min-sdk-version', '31', '--target-sdk-version', '35']
    if resources:
        run(BT/'aapt2', 'compile', '--dir', resources, '-o', work/'resources.zip')
        link += [work/'resources.zip']
        assets = resources.parent/'assets'
        if assets.is_dir(): link += ['-A', assets]
    run(*link)
    javac(list(sources)+list((work/'generated').rglob('*.java')), work/'classes', extra, libs)
    (work/'dex').mkdir()
    dex = [BT/'d8', '--min-api', '31', '--lib', ANDROID, '--output', work/'dex']
    if extra: dex += ['--classpath', extra]
    run(*dex, *libs, *sorted((work/'classes').rglob('*.class')))
    shutil.copy(work/'resources.apk', work/'unsigned.apk')
    with zipfile.ZipFile(work/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as z:
        for item in (work/'dex').glob('*.dex'): z.write(item, item.name)
        if native:
            for lib in sorted(native.glob('*/*.so')):
                z.write(lib, 'lib/'+lib.relative_to(native).as_posix())
    run(BT/'zipalign', '-f', '4', work/'unsigned.apk', work/'aligned.apk')
    key = ROOT/'build/debug.keystore'
    if not key.exists():
        run(JAVA/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=Android Debug,O=AppDodge,C=ZZ')
    out = ROOT/'output'/f'{name}.apk'
    out.parent.mkdir(exist_ok=True)
    run(BT/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', out, work/'aligned.apk')
    run(BT/'apksigner', 'verify', out)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    out.with_suffix('.apk.sha256').write_text(f'{digest}  {out.name}\n')
    print(f'Built: {out}\nSHA-256: {digest}')
    return work/'classes'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device-tests', action='store_true')
    parser.add_argument('--apk-path', action='store_true', help='Print the product APK path without building.')
    args = parser.parse_args()
    app = ROOT/'app/src/main'
    version = ET.parse(app/'AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
    name = f'反人性软件-{version}'
    if args.apk_path:
        print(ROOT/'output'/f'{name}.apk')
        return
    for tool in [JAVA/'bin/javac', ANDROID, BT/'aapt2', BT/'d8', BT/'zipalign', BT/'apksigner']:
        if not tool.exists(): raise SystemExit(f'Missing tool: {tool}. Install JDK 17, Android platform 35 and build-tools 35.0.0.')
    libs = [ROOT/'app/libs/shizuku'/f'{artifact}-13.1.5.jar' for artifact in ('api', 'aidl', 'shared', 'provider')]
    libs.append(ROOT/'app/libs/shizuku/annotation-1.3.0.jar')
    localadb = ROOT/'app/libs/localadb'
    libs += sorted(localadb.glob('*.jar'))
    sources = list((app/'java').rglob('*.java')) + list((localadb/'src').rglob('*.java'))
    classes = apk(name, app/'AndroidManifest.xml', sources, app/'res', libs=libs, native=localadb/'jni')
    if args.device_tests:
        native_checks = ROOT/'build/native-rest-host-tests'
        tests = sorted((ROOT/'tests/native-rest-smoke').glob('*Test.java'))
        javac(tests, native_checks, classes)
        for test in tests:
            run(JAVA/'bin/java', '-classpath', os.pathsep.join(map(str,(native_checks,classes,ANDROID))),
                f'dev.local.appdodge.{test.stem}')
        apk('appdodge-device-tests', ROOT/'tests/android/AndroidManifest.xml', (ROOT/'tests/android/java').rglob('*.java'), extra=classes)

if __name__ == '__main__': main()
