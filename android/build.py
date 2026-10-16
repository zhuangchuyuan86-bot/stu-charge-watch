"""Build a small Android app using only the official JDK/SDK command-line tools."""
from pathlib import Path
import argparse,json,os,subprocess,zipfile,hashlib,shutil
import xml.etree.ElementTree as ET

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--toolchain',required=True,help='JSON paths: java, javac, keytool, aapt2, zipalign, d8, apksigner, android_jar')
    parser.add_argument('--work',required=True)
    parser.add_argument('--apk',required=True)
    args=parser.parse_args()
    source=Path(__file__).resolve().parent
    manifest=ET.parse(source/'AndroidManifest.xml').getroot()
    version_code=manifest.get('{http://schemas.android.com/apk/res/android}versionCode')
    version_name=manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    work=Path(args.work).resolve();work.mkdir(parents=True,exist_ok=True)
    apk=Path(args.apk).resolve();apk.parent.mkdir(parents=True,exist_ok=True)
    tools=json.loads(Path(args.toolchain).read_text(encoding='utf-8'))
    def run(stage,command):
        print(stage,flush=True)
        subprocess.run([str(part) for part in command],check=True)
    for name in ['generated','classes','dex']:
        folder=work/name
        if folder.exists():shutil.rmtree(folder)
        folder.mkdir()
    generated=work/'generated';classes=work/'classes';dex=work/'dex'
    resources=work/'resources.zip';base=work/'base.apk';aligned=work/'aligned.apk'
    lambda_stubs=Path(tools['aapt2']).parent/'core-lambda-stubs.jar'
    bootclasspath=os.pathsep.join([tools['android_jar'],str(lambda_stubs)])
    run('Compiling resources',[tools['aapt2'],'compile','--dir',source/'res','-o',resources])
    run('Linking resources',[tools['aapt2'],'link','-o',base,'-I',tools['android_jar'],'--manifest',source/'AndroidManifest.xml',resources,'--java',generated,'-A',source/'assets','--min-sdk-version','26','--target-sdk-version','35','--version-code',version_code,'--version-name',version_name])
    java_files=list((source/'src').rglob('*.java'))+list(generated.rglob('*.java'))
    java_args=work/'java-sources.txt'
    java_args.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in java_files),encoding='utf-8')
    if tools.get('ecj'):
        run('Compiling Java (Eclipse compiler)',[tools['java'],'-jar',tools['ecj'],'-1.8','-encoding','UTF-8','-bootclasspath',bootclasspath,'-d',classes,'@'+str(java_args)])
    else:
        run('Compiling Java',[tools['javac'],'-encoding','UTF-8','-source','8','-target','8','-bootclasspath',bootclasspath,'-d',classes,'@'+str(java_args)])
    class_jar=work/'classes.jar'
    with zipfile.ZipFile(class_jar,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for path in classes.rglob('*.class'): archive.write(path,path.relative_to(classes).as_posix())
    run('Creating Android bytecode',[tools['java'],'-cp',tools['d8'],'com.android.tools.r8.D8','--release','--lib',tools['android_jar'],'--min-api','26','--output',dex,class_jar])
    with zipfile.ZipFile(base,'a',compression=zipfile.ZIP_DEFLATED) as archive:
        for path in dex.glob('classes*.dex'): archive.write(path,path.name)
    run('Aligning APK',[tools['zipalign'],'-f','4',base,aligned])
    keystore=work/'personal-signing.p12'
    # Personal sideload build. The private key stays in the build work directory.
    password=os.environ.get('CHARGE_SIGNING_PASSWORD','android')
    child_env=dict(os.environ,CHARGE_SIGNING_PASSWORD=password)
    if not keystore.exists():
        print('Creating personal signing key',flush=True)
        subprocess.run([tools['keytool'],'-genkeypair','-keystore',str(keystore),'-storetype','PKCS12','-storepass:env','CHARGE_SIGNING_PASSWORD','-keypass:env','CHARGE_SIGNING_PASSWORD','-alias','campus-charge','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Campus Charge, OU=Personal, O=Personal, C=CN','-noprompt'],env=child_env,check=True)
    print('Signing APK',flush=True)
    subprocess.run([tools['java'],'-jar',tools['apksigner'],'sign','--ks',str(keystore),'--ks-key-alias','campus-charge','--ks-pass','env:CHARGE_SIGNING_PASSWORD','--key-pass','env:CHARGE_SIGNING_PASSWORD','--out',str(apk),str(aligned)],env=child_env,check=True)
    run('Verifying signature',[tools['java'],'-jar',tools['apksigner'],'verify','--verbose',apk])
    run('Verifying ZIP alignment',[tools['zipalign'],'-c','4',apk])
    print(json.dumps({'apk':str(apk),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},indent=2))

if __name__=='__main__':main()
