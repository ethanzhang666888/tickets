#!/usr/bin/env python3
"""Build and stage the dedicated USB executor from this checkout, never download an APK."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

from usb_protocol import PACKAGE, RUNNER, VERSION


def main():
    root = Path(__file__).resolve().parents[2]
    project = root / 'android-companion'
    env = os.environ.copy()
    if not env.get('JAVA_HOME'):
        for path in ('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home', '/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'):
            if Path(path).is_dir():
                env['JAVA_HOME'] = path
                break
    if not env.get('ANDROID_HOME'):
        for path in (Path('/opt/homebrew/share/android-commandlinetools'), Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'):
            if path.is_dir():
                env['ANDROID_HOME'] = str(path)
                break
    wrapper = [str(project / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(project / 'gradlew')]
    subprocess.run(wrapper + [':app:assembleUsbExecution', '--console=plain'], cwd=project, env=env, check=True)
    source = project / 'app/build/outputs/apk/usbExecution/app-usbExecution.apk'
    manifest_path = project / 'app/build/intermediates/merged_manifests/usbExecution/processUsbExecutionManifest/AndroidManifest.xml'
    manifest = ET.parse(manifest_path).getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    if manifest.get('package') != PACKAGE:
        raise RuntimeError('USB application package mismatch')
    permission_names = {p.get(android + 'name') for p in manifest.findall('uses-permission')}
    denied = {'android.permission.INTERNET', 'android.permission.INJECT_EVENTS', 'android.permission.FOREGROUND_SERVICE',
              'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION', 'android.permission.WRITE_SECURE_SETTINGS'}
    if permission_names & denied:
        raise RuntimeError('USB executor must not request network, injection, foreground or settings privileges')
    application = manifest.find('application')
    if application is None or application.get(android + 'debuggable', 'false') != 'false':
        raise RuntimeError('USB executor must not be debuggable')
    components = {item.get(android + 'name', '') for item in application}
    if any(name.endswith(('.MainActivity', '.ExecutionActivity', '.CaptureService', '.InspectorAccessibilityService', '.ExecutionStopReceiver')) for name in components):
        raise RuntimeError('Normal-app components leaked into USB executor manifest')
    entries = manifest.findall('instrumentation')
    if len(entries) != 1 or entries[0].get(android + 'name') != RUNNER.split('/', 1)[1] or entries[0].get(android + 'targetPackage') != PACKAGE:
        raise RuntimeError('USB instrumentation identity mismatch')
    destination = root / 'tools/android/usb-executor'
    destination.mkdir(parents=True, exist_ok=True)
    apk = destination / 'app-usb-executor.apk'
    temporary = apk.with_suffix('.apk.pending')
    shutil.copyfile(source, temporary)
    digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
    metadata = {'protocol': VERSION, 'package': PACKAGE, 'runner': RUNNER, 'apk': apk.name, 'sha256': digest,
                'versionCode': int(manifest.get(android + 'versionCode')), 'versionName': manifest.get(android + 'versionName'),
                'automaticSubmissionSupported': False, 'startup': 'authorized_adb_instrumentation'}
    pending_manifest = destination / 'manifest.json.pending'
    pending_manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, apk)
    os.replace(pending_manifest, destination / 'manifest.json')
    print(f'USB executor staged: {apk.name}; protocol {VERSION}; no automatic submission')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'USB executor build failed: {error}', file=sys.stderr)
        raise SystemExit(1)
