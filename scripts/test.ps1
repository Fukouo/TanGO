$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Join-Path (Get-ChildItem '.tools/java' -Directory | Select-Object -First 1).FullName 'bin' }
$jsonJar = Join-Path $projectRoot '.tools/json.jar'
if (!(Test-Path -LiteralPath $jsonJar)) { throw 'Download org.json:json:20250107 into .tools/json.jar before running these JVM checks.' }
New-Item -ItemType Directory -Force '.tools/test-classes' | Out-Null
& "$javaBin/javac.exe" -encoding UTF-8 -cp $jsonJar -d '.tools/test-classes' 'app/src/main/java/com/tango/nihongo/LookupRules.java' 'app/src/main/java/com/tango/nihongo/AiClient.java' 'app/src/main/java/com/tango/nihongo/PlanProtocol.java' 'app/src/main/java/com/tango/nihongo/LoopbackLogin.java' 'app/src/main/java/com/tango/nihongo/UsageData.java' 'app/src/main/java/com/tango/nihongo/LocalSearch.java' 'tests/LocalSearchTest.java' 'app/src/main/java/com/tango/nihongo/SearchLadder.java' 'tests/SearchLadderTest.java' 'tests/SearchLadderHttpTest.java' 'tests/LookupRulesTest.java' 'tests/AiClientTest.java' 'tests/PlanProtocolTest.java' 'tests/UsageDataTest.java'
if ($LASTEXITCODE -ne 0) { throw 'JVM compilation failed' }
$classpath = ".tools/test-classes;$jsonJar"
& "$javaBin/java.exe" -cp $classpath com.tango.nihongo.LookupRulesTest
if ($LASTEXITCODE -ne 0) { throw 'Rule checks failed' }
if (!(Test-Path '.tools/fixture.p12')) {
    & "$javaBin/keytool.exe" -genkeypair -alias fixture -keyalg RSA -storetype PKCS12 -keystore '.tools/fixture.p12' -storepass fixture-only -keypass fixture-only -dname 'CN=localhost' -ext 'SAN=dns:localhost' -validity 30 -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Fixture certificate generation failed' }
}
& "$javaBin/java.exe" --add-modules jdk.httpserver -cp $classpath com.tango.nihongo.AiClientTest '.tools/fixture.p12'
if ($LASTEXITCODE -ne 0) { throw 'Mock API checks failed' }
& "$javaBin/java.exe" -cp $classpath com.tango.nihongo.PlanProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'ChatGPT protocol checks failed' }
& "$javaBin/java.exe" -cp $classpath com.tango.nihongo.UsageDataTest
if ($LASTEXITCODE -ne 0) { throw 'Usage counter checks failed' }

& "$javaBin/java.exe" -cp $classpath com.tango.nihongo.LocalSearchTest
if ($LASTEXITCODE -ne 0) { throw 'Local search and period checks failed' }

& "$javaBin/java.exe" -cp $classpath com.tango.nihongo.SearchLadderTest
if ($LASTEXITCODE -ne 0) { throw 'Search ladder checks failed' }
& "$javaBin/java.exe" --add-modules jdk.httpserver -cp $classpath com.tango.nihongo.SearchLadderHttpTest '.tools/fixture.p12'
if ($LASTEXITCODE -ne 0) { throw 'Search ladder HTTPS checks failed' }
