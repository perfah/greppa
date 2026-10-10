# Discovers all IntelliJ Platform jars under the IDE installation,
# including the bundled plugins. The Markdown parser used by
# MarkdownParser (org.intellij.markdown) ships in the bundled Markdown
# plugin (plugins/markdown/lib/markdown.jar), not under lib, so the
# plugins directory must be scanned too or the classpath jar built from
# this output will not contain it.
# Outputs one jar path per line.

$ideaHome = "C:\Program Files\JetBrains\RustRover 2024.2.3"

if (-not (Test-Path $ideaHome)) {
    Write-Error "IDEA_HOME does not exist: $ideaHome"
    exit 1
}

# Platform jars live under lib; bundled plugin jars (including markdown.jar)
# live under plugins/<plugin>/lib.
$libDir = Join-Path $ideaHome "lib"
$pluginsDir = Join-Path $ideaHome "plugins"
foreach ($dir in @($libDir, $pluginsDir)) {
    if (-not (Test-Path $dir)) {
        Write-Error "Directory does not exist: $dir"
        exit 1
    }
}

Write-Host "Discovering all jars under $libDir and $pluginsDir"

$jars = Get-ChildItem -Path $libDir, $pluginsDir -Recurse -Filter "*.jar" |
    Select-Object -ExpandProperty FullName |
    Sort-Object -Unique

if (-not $jars) {
    Write-Error "No jars found under $libDir or $pluginsDir"
    exit 1
}

Write-Host "Found $($jars.Count) jars"

foreach ($jar in $jars) {
    Write-Output $jar
}
exit 0
