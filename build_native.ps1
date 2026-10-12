$ErrorActionPreference = "Stop"

$ndkHome = $env:ANDROID_NDK_HOME
if (-not $ndkHome) {
    $ndkHome = $env:ANDROID_NDK_ROOT
}
if (-not $ndkHome) {
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if (-not $sdkRoot) {
        $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
    }
    $ndkDirs = Get-ChildItem "$sdkRoot\ndk" -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    if ($ndkDirs) {
        $ndkHome = $ndkDirs[0].FullName
    }
}

if (-not $ndkHome -or -not (Test-Path $ndkHome)) {
    Write-Error "Android NDK not found. Please set ANDROID_NDK_HOME environment variable or ensure Android NDK is installed in Android SDK."
    exit 1
}

$ndkBin = "$ndkHome\toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $ndkBin)) {
    Write-Error "NDK toolchain bin directory not found at $ndkBin"
    exit 1
}

$repoRoot = $PSScriptRoot
$targets = @(
    @{ Abi = "arm64-v8a"; Arch = "arm64"; Arm = ""; Cc = "aarch64-linux-android24-clang" },
    @{ Abi = "armeabi-v7a"; Arch = "arm"; Arm = "7"; Cc = "armv7a-linux-androideabi24-clang" },
    @{ Abi = "x86_64"; Arch = "amd64"; Arm = ""; Cc = "x86_64-linux-android24-clang" }
)

$cSrc = "$repoRoot\app\src\main\native\tcprelay\tcprelay.c"

foreach ($target in $targets) {
    $abi = $target.Abi
    $arch = $target.Arch
    $arm = $target.Arm
    $cc = $target.Cc

    $outDir = "$repoRoot\app\src\main\jniLibs\$abi"
    if (-not (Test-Path $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }

    Write-Host "Compiling pure C libtcprelay.so for $abi with 16KB alignment..."
    & "$ndkBin\$cc.cmd" -shared -fPIC -O3 -Wall "-Wl,-z,max-page-size=16384" $cSrc -o "$outDir\libtcprelay.so" -llog

    Write-Host "Building Go libtailcat.so for $abi with 16KB alignment..."
    $env:GOOS = "android"
    $env:GOARCH = $arch
    if ($arm) { $env:GOARM = $arm } else { Remove-Item Env:\GOARM -ErrorAction SilentlyContinue }
    $env:CGO_ENABLED = "1"
    $env:CC = "$ndkBin\$cc.cmd"

    Push-Location "$repoRoot\app\src\main\native\tailcat-bridge"
    go build -buildmode=c-shared "-ldflags=-extldflags=-Wl,-z,max-page-size=16384" -o "$outDir\libtailcat.so" main.go
    Pop-Location
}

Write-Host "All native modules built successfully!"
