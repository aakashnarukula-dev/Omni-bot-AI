#!/usr/bin/env python3
# Disposable Android emulator only; never uninstalls the personal package.
import subprocess,json,os,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];out=root/'build/google-backup'
out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ['ANDROID_HOME'])
serial=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
adb=[str(sdk/'platform-tools/adb'),'-s',serial]
package='com.gyftalala.omni.verification'
assert subprocess.check_output(adb+['shell','getprop','ro.kernel.qemu'],text=True).strip()=='1'
def run(args):
 p=subprocess.run(adb+args,text=True,capture_output=True,check=True)
 return p.stdout

def phase(method,arg,name):
 result=run(['shell','am','instrument','-w','-e','class','com.gyftalala.omni.CloudBackupIntegrationTest#'+method,'-e','cloudPhase',arg,package+'.test/com.gyftalala.omni.OmniTestRunner'])
 (out/name).write_text(result)
 assert 'OK (1 test)' in result and 'FAILURES' not in result and 'INSTRUMENTATION_STATUS_CODE: -4' not in result,result
phase('portabilitySeed','seed','portability-seed.txt')
run(['uninstall',package])
run(['install',str(root/'app/build/outputs/apk/debug/app-debug.apk')])
phase('portabilityRecoverAfterReinstall','recover','portability-reinstall.txt')
run(['shell','pm','clear',package])
phase('portabilityRecoverAfterReinstall','recover','portability-clear-data.txt')
(out/'portability-results.json').write_text(json.dumps({'passed':['Encrypted upload through SDK','Same Google account discovers backup after uninstall/reinstall','Same Google account discovers backup after clearing data','No recovery password; explicit restore confirmation required','Private card data recovered with new installation key','Daily backup enabled after recovery'],'syntheticOnly':True,'project':'demo-omni-backup'},indent=2)+'\n')
print('PASS: cloud recovery after reinstall and clearing data')
