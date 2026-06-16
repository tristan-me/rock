$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $Root
$SdkCandidates = @(
    (Join-Path $Root ".android-sdk"),
    (Join-Path $RepoRoot "android_catcher_apk\.android-sdk"),
    (Join-Path $RepoRoot "android_api_catcher_apk\.android-sdk")
)

$Sdk = $null
foreach ($Candidate in $SdkCandidates) {
    if (Test-Path -LiteralPath (Join-Path $Candidate "platforms\android-35\android.jar")) {
        $Sdk = $Candidate
        break
    }
}
if ($null -eq $Sdk) {
    throw "Android SDK not found. Expected one of: $($SdkCandidates -join ', ')"
}

$BuildTools = Join-Path $Sdk "build-tools\35.0.0"
$AndroidJar = Join-Path $Sdk "platforms\android-35\android.jar"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$Zipalign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Build = Join-Path $Root "manual-build"
$OutDir = Join-Path $Root "app\build\outputs\apk\debug"
$FinalApk = Join-Path $OutDir "app-debug.apk"
$Keystore = Join-Path $Root "debug.keystore"

function Assert-LastExit($Name) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

foreach ($Path in @($AndroidJar, $Aapt2, $D8, $Zipalign, $ApkSigner)) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing required file: $Path"
    }
}

Remove-Item -LiteralPath $Build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Build, $OutDir | Out-Null

$CompiledRes = Join-Path $Build "compiled-res.zip"
$Generated = Join-Path $Build "generated"
$BaseApk = Join-Path $Build "base.apk"
New-Item -ItemType Directory -Force -Path $Generated | Out-Null

& $Aapt2 compile --dir (Join-Path $Root "app\src\main\res") -o $CompiledRes
Assert-LastExit "aapt2 compile"
& $Aapt2 link `
    -o $BaseApk `
    -I $AndroidJar `
    --manifest (Join-Path $Root "app\src\main\AndroidManifest.xml") `
    -R $CompiledRes `
    --java $Generated `
    --auto-add-overlay `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    --version-code 3 `
    --version-name 0.3.0
Assert-LastExit "aapt2 link"

$ClassesDir = Join-Path $Build "classes"
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$SourcesFile = Join-Path $Build "sources.txt"
$SourceFiles = @()
$SourceFiles += Get-ChildItem -LiteralPath (Join-Path $Root "app\src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$SourceFiles += Get-ChildItem -LiteralPath $Generated -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$SourceFiles | Set-Content -LiteralPath $SourcesFile -Encoding ASCII

javac -encoding UTF-8 -source 8 -target 8 -classpath $AndroidJar -d $ClassesDir "@$SourcesFile"
Assert-LastExit "javac"

$ClassesJar = Join-Path $Build "classes.jar"
Push-Location $ClassesDir
jar cf $ClassesJar .
Assert-LastExit "jar classes"
Pop-Location

$DexDir = Join-Path $Build "dex"
New-Item -ItemType Directory -Force -Path $DexDir | Out-Null
& $D8 --min-api 26 --output $DexDir $ClassesJar
Assert-LastExit "d8"

$ApkAdd = Join-Path $Build "apk-add"
New-Item -ItemType Directory -Force -Path $ApkAdd | Out-Null
Copy-Item -LiteralPath (Join-Path $DexDir "classes.dex") -Destination (Join-Path $ApkAdd "classes.dex")
Push-Location $ApkAdd
jar uf $BaseApk classes.dex
Assert-LastExit "jar apk update"
Pop-Location

$AlignedApk = Join-Path $Build "aligned.apk"
& $Zipalign -f 4 $BaseApk $AlignedApk
Assert-LastExit "zipalign"

if (-not (Test-Path -LiteralPath $Keystore)) {
    keytool -genkeypair `
        -keystore $Keystore `
        -storepass android `
        -keypass android `
        -alias androiddebugkey `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" `
        -storetype JKS | Out-Null
}

& $ApkSigner sign `
    --ks $Keystore `
    --ks-pass pass:android `
    --key-pass pass:android `
    --out $FinalApk `
    $AlignedApk
Assert-LastExit "apksigner sign"

& $ApkSigner verify --verbose $FinalApk
Assert-LastExit "apksigner verify"
Write-Host "APK: $FinalApk"
