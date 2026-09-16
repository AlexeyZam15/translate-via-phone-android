# check-project.ps1
# Diagnoses Android project structure.
# Read-only. Does not delete or modify anything.

param(
    [string]$ProjectPath = "P:\Users\Alexey\AndroidStudioProjects\ScreenTranslator2"
)

$ErrorActionPreference = "Continue"

function Write-OK   { param($msg) Write-Host "[OK]   $msg" -ForegroundColor Green }
function Write-FAIL { param($msg) Write-Host "[FAIL] $msg" -ForegroundColor Red }
function Write-WARN { param($msg) Write-Host "[WARN] $msg" -ForegroundColor Yellow }

Write-Host ""
Write-Host "=== Project check: $ProjectPath ===" -ForegroundColor White
Write-Host ""

# 1. Root exists
if (-not (Test-Path $ProjectPath)) {
    Write-FAIL "Project root not found: $ProjectPath"
    exit 1
}
Write-OK "Project root exists"

# 2. settings.gradle.kts
$settingsPath = Join-Path $ProjectPath "settings.gradle.kts"
if (Test-Path $settingsPath) {
    Write-OK "settings.gradle.kts found"
    $settingsContent = Get-Content $settingsPath -Raw
    if ($settingsContent -match 'include\s*\(\s*":app"\s*\)') {
        Write-OK "  contains include(':app')"
    } else {
        Write-FAIL "  MISSING include(':app') - this is the cause of mainManifest error"
    }
    if ($settingsContent -match 'rootProject\.name\s*=\s*"ScreenTranslator2"') {
        Write-OK "  rootProject.name = ScreenTranslator2"
    } else {
        Write-WARN "  rootProject.name is not ScreenTranslator2 (minor)"
    }
    if ($settingsContent -match 'jitpack\.io') {
        Write-OK "  JitPack repository present"
    } else {
        Write-WARN "  JitPack NOT present - Tesseract will not resolve"
    }
} else {
    Write-FAIL "settings.gradle.kts NOT found in root"
}

# 3. Root build.gradle.kts
$rootBuildPath = Join-Path $ProjectPath "build.gradle.kts"
if (Test-Path $rootBuildPath) {
    Write-OK "Root build.gradle.kts found"
    $rootBuild = Get-Content $rootBuildPath -Raw
    if ($rootBuild -match 'android\s*\{') {
        Write-FAIL "  contains 'android { }' block - should NOT be in root file"
    } else {
        Write-OK "  no 'android { }' block in root"
    }
    if ($rootBuild -match 'alias\(libs\.plugins\.android\.application\)') {
        Write-OK "  contains alias for android.application"
    } else {
        Write-WARN "  does not contain alias for android.application"
    }
} else {
    Write-FAIL "Root build.gradle.kts NOT found"
}

# 4. app folder
$appDir = Join-Path $ProjectPath "app"
if (Test-Path $appDir) {
    Write-OK "app\ folder exists"
} else {
    Write-FAIL "app\ folder NOT found - Gradle looks for manifest in root"
}

# 5. app/build.gradle.kts
$appBuildPath = Join-Path $appDir "build.gradle.kts"
if (Test-Path $appBuildPath) {
    Write-OK "app\build.gradle.kts found"
    $appBuild = Get-Content $appBuildPath -Raw
    if ($appBuild -match 'release\(36\)') {
        Write-OK "  compileSdk = 36"
    } elseif ($appBuild -match 'release\(37\)') {
        Write-WARN "  compileSdk = 37 (AGP 9.0.0 warning)"
    } else {
        Write-WARN "  compileSdk not standard"
    }
    if ($appBuild -match 'text-recognition-cyrillic') {
        Write-FAIL "  contains dependency text-recognition-cyrillic (does not exist!)"
    }
    if ($appBuild -match 'tesseract4android') {
        Write-OK "  contains tesseract4android"
    }
    if ($appBuild -match 'text-recognition') {
        Write-OK "  contains mlkit text-recognition"
    }
} else {
    Write-FAIL "app\build.gradle.kts NOT found - maybe it is in root"
}

# 6. AndroidManifest.xml
$manifestInApp  = Join-Path $appDir "src\main\AndroidManifest.xml"
$manifestInRoot = Join-Path $ProjectPath "src\main\AndroidManifest.xml"

if (Test-Path $manifestInApp) {
    Write-OK "AndroidManifest.xml in app\src\main\ (correct)"
} else {
    Write-FAIL "AndroidManifest.xml NOT in app\src\main\"
}

if (Test-Path $manifestInRoot) {
    Write-FAIL "AndroidManifest.xml is in root src\main\ (WRONG!)"
}

# 7. Kotlin files
$ktDir = Join-Path $appDir "src\main\java\com\example\screentranslator"
$expectedKt = @(
    "MainActivity.kt",
    "OcrEngine.kt",
    "OcrEngineFactory.kt",
    "OverlayService.kt",
    "ZoneOverlayView.kt",
    "engines\MlKitOcrEngine.kt",
    "engines\TesseractOcrEngine.kt"
)
if (Test-Path $ktDir) {
    Write-OK "Kotlin sources folder exists"
    foreach ($f in $expectedKt) {
        $full = Join-Path $ktDir $f
        if (Test-Path $full) {
            Write-OK "  $f"
        } else {
            Write-WARN "  missing: $f"
        }
    }
} else {
    Write-WARN "Kotlin sources folder not found: $ktDir"
}

# 8. Layout files
$layoutDir = Join-Path $appDir "src\main\res\layout"
foreach ($f in @("activity_main.xml", "floating_icon.xml")) {
    $full = Join-Path $layoutDir $f
    if (Test-Path $full) {
        Write-OK "layout\$f found"
    } else {
        Write-WARN "layout\$f missing"
    }
}

# 9. assets/tessdata/eng.traineddata
$trainedData = Join-Path $appDir "src\main\assets\tessdata\eng.traineddata"
if (Test-Path $trainedData) {
    $size = (Get-Item $trainedData).Length
    Write-OK ("eng.traineddata found ({0:N2} MB)" -f ($size / 1MB))
} else {
    Write-WARN "eng.traineddata NOT found - Tesseract will fail at runtime"
}

# 10. libs.versions.toml
$tomlPath = Join-Path $ProjectPath "gradle\libs.versions.toml"
if (Test-Path $tomlPath) {
    Write-OK "gradle\libs.versions.toml found"
    $toml = Get-Content $tomlPath -Raw
    if ($toml -match 'mlkitCyrillic') {
        Write-FAIL "  contains mlkitCyrillic (must be removed)"
    }
    if ($toml -match 'text-recognition-cyrillic') {
        Write-FAIL "  contains text-recognition-cyrillic (package does not exist)"
    }
    if ($toml -match 'tesseract4android') {
        Write-OK "  contains tesseract4android"
    }
} else {
    Write-FAIL "gradle\libs.versions.toml NOT found"
}

# 11. gradle.properties
$propsPath = Join-Path $ProjectPath "gradle.properties"
if (Test-Path $propsPath) {
    Write-OK "gradle.properties found"
} else {
    Write-WARN "gradle.properties NOT found"
}

Write-Host ""
Write-Host "=== Check finished ===" -ForegroundColor White
Write-Host ""
Write-Host "Interpretation:"
Write-Host "  [OK]   - fine"
Write-Host "  [FAIL] - must fix"
Write-Host "  [WARN] - not critical"
Write-Host ""