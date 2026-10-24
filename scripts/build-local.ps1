# Dependency-free fallback build using the official Android SDK build tools.
param([string]$SdkRoot, [string]$JdkRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
if (!$JdkRoot) { $JdkRoot = $env:JAVA_HOME }
if (!$JdkRoot -and (Test-Path '.tools/java')) { $JdkRoot = (Get-ChildItem '.tools/java' -Directory | Select-Object -First 1).FullName }
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot) { $SdkRoot = Join-Path $projectRoot '.tools/android-sdk' }
$env:JAVA_HOME = $JdkRoot
$javaBin = Join-Path $JdkRoot 'bin'
$tools = Join-Path $SdkRoot 'build-tools/35.0.0'
$androidJar = Join-Path $SdkRoot 'platforms/android-35/android.jar'
if (!(Test-Path $androidJar) -or !(Test-Path "$tools/aapt2.exe")) { throw 'Android Platform 35 and Build Tools 35.0.0 are required.' }
function Run-Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Failed: $exe" }
}
$build = Join-Path $projectRoot 'app/build/manual'
New-Item -ItemType Directory -Force "$build/classes","$build/generated","$build/dex",'app/build/outputs/apk/debug' | Out-Null
$manifest = [System.IO.File]::ReadAllText((Join-Path $projectRoot 'app/src/main/AndroidManifest.xml')).Replace('<manifest ', '<manifest package="com.tango.nihongo" ')
[System.IO.File]::WriteAllText("$build/AndroidManifest.xml",$manifest,[System.Text.UTF8Encoding]::new($false))
Run-Checked "$tools/aapt2.exe" @('compile','--dir','app/src/main/res','-o',"$build/resources.zip")
Run-Checked "$tools/aapt2.exe" @('link','-I',$androidJar,'--manifest',"$build/AndroidManifest.xml",'--java',"$build/generated",'--min-sdk-version','26','--target-sdk-version','35','--version-code','19','--version-name','1.9.1','-o',"$build/unsigned.apk","$build/resources.zip")
$sources = @(Get-ChildItem 'app/src/main/java',"$build/generated" -Filter '*.java' -Recurse | ForEach-Object FullName)
Run-Checked "$javaBin/javac.exe" (@('-encoding','UTF-8','--release','17','-classpath',$androidJar,'-d',"$build/classes") + $sources)
Run-Checked "$javaBin/jar.exe" @('cf',"$build/classes.jar",'-C',"$build/classes",'.')
Run-Checked "$tools/d8.bat" @('--lib',$androidJar,'--min-api','26','--output',"$build/dex","$build/classes.jar")
Run-Checked "$javaBin/jar.exe" @('uf',"$build/unsigned.apk",'-C',"$build/dex",'classes.dex')
Run-Checked "$tools/zipalign.exe" @('-f','4',"$build/unsigned.apk","$build/aligned.apk")
$keystore = Join-Path $projectRoot '.tools/debug.p12'
if (!(Test-Path $keystore)) {
    Run-Checked "$javaBin/keytool.exe" @('-genkeypair','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-storetype','PKCS12','-keystore',$keystore,'-storepass','android','-keypass','android','-dname','CN=Android Debug,O=Android,C=US','-validity','10000','-noprompt')
}
$apk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
Run-Checked "$tools/apksigner.bat" @('sign','--ks',$keystore,'--ks-pass','pass:android','--out',$apk,"$build/aligned.apk")
Run-Checked "$tools/apksigner.bat" @('verify','--verbose',$apk)
Run-Checked "$tools/zipalign.exe" @('-c','4',$apk)
Write-Output "APK: $apk"
