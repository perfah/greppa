# Build script for the Greppa JetBrains plugin on Windows.
# Run this from the greppa\ directory.
#
# Besides compiling, the script verifies that the produced jar really
# contains the current sources (the Topic/goal field, the trigger-file
# mechanism and the tab-switch listener registration) and copies it
# over the jar installed in the IDE. A rebuild can therefore never
# silently leave an old plugin running: either it fails loudly, or the
# IDE runs the new build after the next restart.
#
# The built jar is located by name pattern (greppa-*.jar), so the script
# works no matter which version pom.xml declares; the version in pom.xml and
# plugin.xml is only the human-visible proof in Settings | Plugins.

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$discoverScript = Join-Path $scriptDir "discover-jars.ps1"

if (-not (Test-Path $discoverScript)) {
    Write-Error "Cannot find discover-jars.ps1 at $discoverScript"
    exit 1
}

$ideaHome = "C:\Program Files\JetBrains\RustRover 2024.2.3"
$libDir = Join-Path $ideaHome "lib"

Write-Host "Discovering IntelliJ Platform jars..."
$jars = & $discoverScript
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to discover required jars. See errors above."
    exit 1
}

if (-not $jars) {
    Write-Error "No jar paths were discovered."
    exit 1
}

Write-Host "Found $($jars.Count) jars"

# Hardcoded JDK path. Must contain bin\javac.exe and bin\jar.exe.
$javaHome = "C:\Program Files\Java\jdk-26.0.2.1"

if (-not (Test-Path "$javaHome\bin\javac.exe")) {
    Write-Error "JDK not found at $javaHome\bin\javac.exe"
    exit 1
}
if (-not (Test-Path "$javaHome\bin\jar.exe")) {
    Write-Error "JDK jar tool not found at $javaHome\bin\jar.exe"
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
& "$javaHome\bin\java.exe" -version
& "$javaHome\bin\javac.exe" -version

# Build a classpath jar in the project directory. The manifest Class-Path uses
# absolute file URLs so the jar can live anywhere and still reference the IDE jars.
$classpathJar = Join-Path $scriptDir "ide-classpath.jar"
$manifestFile = Join-Path $scriptDir "ide-classpath.manifest"

function ConvertTo-FileUrl($path) {
    $full = (Resolve-Path $path).Path
    $full = $full.Replace('\', '/')
    $full = $full.Replace(' ', '%20')
    $full = $full.Replace('#', '%23')
    return "file:///$full"
}

$entries = $jars | ForEach-Object { ConvertTo-FileUrl $_ }

function Write-Manifest($path, $classPathEntries) {
    # Build the full Class-Path attribute value.
    $value = "Class-Path: " + ($classPathEntries -join " ")

    # JAR manifest lines are limited to 72 bytes. Continuation lines start with
    # a single space that is stripped by the parser, so reserve one byte for it.
    $maxFirst = 72
    $maxCont = 71

    $lines = New-Object System.Collections.Generic.List[System.String]
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($value)
    $offset = 0
    $first = $true
    while ($offset -lt $bytes.Length) {
        $take = if ($first) { $maxFirst } else { $maxCont }
        if ($offset + $take -gt $bytes.Length) {
            $take = $bytes.Length - $offset
        }
        $chunk = [System.Text.Encoding]::ASCII.GetString($bytes, $offset, $take)
        if ($first) {
            $lines.Add($chunk)
        } else {
            $lines.Add(" " + $chunk)
        }
        $offset += $take
        $first = $false
    }

    # Manifest files must end with a blank line.
    $lines.Add("")

    [System.IO.File]::WriteAllLines($path, $lines, [System.Text.Encoding]::ASCII)
}

Write-Manifest $manifestFile $entries

Write-Host "Creating classpath jar $classpathJar"
& "$javaHome\bin\jar.exe" cfm $classpathJar $manifestFile
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to create classpath jar."
    exit 1
}

$mvn = "C:\Users\PF\scoop\apps\maven\current\bin\mvn.cmd"
if (-not (Test-Path $mvn)) {
    Write-Error "Maven not found at $mvn"
    exit 1
}

$mvnArgs = @(
    "-Didea.classpath.jar=$classpathJar",
    "-Dmaven.compiler.executable=`"$javaHome\bin\javac.exe`"",
    "-Dmaven.compiler.fork=true",
    "clean",
    "package"
)

Write-Host ""
Write-Host "Running Maven:"
Write-Host "$mvn $($mvnArgs -join ' ')"
Write-Host ""

# The first Maven run downloads the build plugins, so it needs internet
# access once; later runs work offline from the local repository.
& $mvn $mvnArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed."
    exit 1
}

# ---------------------------------------------------------------------------
# Locate the freshly built plugin jar. 'clean' ran first, so exactly one
# greppa-*.jar exists in target, whatever version pom.xml declares.
# ---------------------------------------------------------------------------

$targetDir = Join-Path $scriptDir "target"
$pluginJarItem = Get-ChildItem -Path $targetDir -Filter "greppa-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $pluginJarItem) {
    Write-Error "Maven finished but no greppa-*.jar was found in $targetDir."
    exit 1
}
$pluginJar = $pluginJarItem.FullName
Write-Host ""
Write-Host "Plugin jar built: $pluginJar"

# ---------------------------------------------------------------------------
# Verify the jar contains the current classes. These checks exist because the
# plugin has been 'rebuilt' several times while the IDE kept running an old
# jar: the script used to abort before Maven (a $LASTEXITSTATUS/$LASTEXITCODE
# typo) and never installed the jar. Every such failure is now loud, and a
# failed check names the exact source file that is still the old version.
# ---------------------------------------------------------------------------

Write-Host "Verifying that the jar contains the current classes..."
$jarEntries = & "$javaHome\bin\jar.exe" tf $pluginJar
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to list the contents of $pluginJar."
    exit 1
}
$requiredClasses = @(
    "com/example/greppa/HintsPanel.class",
    "com/example/greppa/LspTabSelectionListener.class",
    "com/example/greppa/HintsFilePoller.class"
)
foreach ($class in $requiredClasses) {
    if ($jarEntries -notcontains $class) {
        Write-Error "The plugin jar does not contain $class. The sources on disk are stale: apply the delivered file listings and rebuild."
        exit 1
    }
}

# The Topic/goal input must be inside the compiled HintsPanel. If it is
# missing, the HintsPanel.java on disk is still the old version and the
# field can never appear in the IDE.
$panelMembers = & "$javaHome\bin\javap.exe" -p -classpath $pluginJar com.example.greppa.HintsPanel
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to inspect HintsPanel.class inside the plugin jar."
    exit 1
}
if (-not ($panelMembers | Select-String -SimpleMatch "topicField")) {
    Write-Error "The compiled HintsPanel has no topicField: HintsPanel.java on disk is still the old version. Apply the delivered file listing and rebuild."
    exit 1
}

# The poller must have the trigger file writer. If not, the
# HintsFilePoller.java on disk is still the old version and the reload
# button cannot ask the server for a fresh analysis.
$pollerMembers = & "$javaHome\bin\javap.exe" -p -classpath $pluginJar com.example.greppa.HintsFilePoller
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to inspect HintsFilePoller.class inside the plugin jar."
    exit 1
}
if (-not ($pollerMembers | Select-String -SimpleMatch "writeTriggerFile")) {
    Write-Error "The compiled HintsFilePoller has no writeTriggerFile method: HintsFilePoller.java on disk is still the old version. Apply the delivered file listing and rebuild."
    exit 1
}

# The tab-switch listener must use the trigger file mechanism. If not,
# the LspTabSelectionListener.java on disk is still the old version and
# tab switches keep doing nothing.
$tabBytecode = & "$javaHome\bin\javap.exe" -c -p -classpath $pluginJar com.example.greppa.LspTabSelectionListener
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to inspect LspTabSelectionListener.class inside the plugin jar."
    exit 1
}
if (-not ($tabBytecode | Select-String -SimpleMatch "writeTriggerFile")) {
    Write-Error "The compiled LspTabSelectionListener does not use the trigger file mechanism: LspTabSelectionListener.java on disk is still the old version. Apply the delivered file listing and rebuild."
    exit 1
}

# The tab-switch fix must be inside the compiled factory: it registers the
# per-project listeners (HintsService.initForProject plus the
# LspTabSelectionListener subscription). If it is missing, tab switches
# keep doing nothing because the HintsToolWindowFactory.java on disk is
# still the old version.
$factoryBytecode = & "$javaHome\bin\javap.exe" -c -p -classpath $pluginJar com.example.greppa.HintsToolWindowFactory
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to inspect HintsToolWindowFactory.class inside the plugin jar."
    exit 1
}
if (-not ($factoryBytecode | Select-String -SimpleMatch "initForProject")) {
    Write-Error "The compiled HintsToolWindowFactory does not register the per-project listeners: HintsToolWindowFactory.java on disk is still the old version. Apply the delivered file listing and rebuild."
    exit 1
}
Write-Host "Verified: the jar contains the Topic/goal field, the trigger-file mechanism and the tab-switch listener registration."

# ---------------------------------------------------------------------------
# Install: copy the fresh jar over the jar installed in the IDE. Plugins
# installed via 'Install Plugin from Disk' live under
# %APPDATA%\JetBrains\<product>\plugins\...\greppa*.jar. The installed
# file name is kept: the IDE reads the plugin id and version from the
# plugin.xml inside the jar, not from the file name.
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "Looking for the installed Greppa plugin jar..."
$installedJars = @()
$jetbrainsConfig = Join-Path $env:APPDATA "JetBrains"
if (Test-Path $jetbrainsConfig) {
    $installedJars = @(Get-ChildItem -Path $jetbrainsConfig -Recurse -Filter "greppa*.jar" -ErrorAction SilentlyContinue)
}

if ($installedJars.Count -eq 0) {
    Write-Host "No installed Greppa jar found under $jetbrainsConfig."
    Write-Host "Install the plugin once manually:"
    Write-Host "  1. Start RustRover."
    Write-Host "  2. Open Settings | Plugins, click the gear icon, choose 'Install Plugin from Disk...'."
    Write-Host "  3. Select $pluginJar"
    Write-Host "  4. Restart the IDE."
    Write-Host "From the next build on, this script updates the installed jar automatically."
} else {
    $ideRunning = Get-Process -Name "rustrover*" -ErrorAction SilentlyContinue
    if ($ideRunning) {
        Write-Warning "RustRover is currently running. The old plugin stays loaded until the IDE restarts, and a running IDE may keep the jar file locked."
    }
    foreach ($installed in $installedJars) {
        try {
            Copy-Item -LiteralPath $pluginJar -Destination $installed.FullName -Force -ErrorAction Stop
            Write-Host "Updated installed plugin jar: $($installed.FullName)"
        } catch {
            Write-Warning "Could not update $($installed.FullName): $($_.Exception.Message)"
            Write-Warning "Close the IDE and run this script again."
        }
    }
}

Write-Host ""
Write-Host "Build complete and verified: $pluginJar"
Write-Host "Restart the IDE; the Greppa Hints tool window then shows the Topic/goal input at its top."
Write-Host "Settings | Plugins must show version 1.0.3 after the restart; if it still shows 1.0.2, the IDE is running the previous jar."
