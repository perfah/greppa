# Throwaway diagnostic for the PasswordSafe compile failure.
# 1) Scans every jar under the IDE installation and prints every jar that
#    contains com/intellij/credentialStore/* classes or anything named
#    *PasswordSafe* - so we learn where CredentialAttributes/Credentials
#    come from and whether PasswordSafe.class exists at all, and where.
# 2) Compiles a one-line test class against the generated ide-classpath.jar
#    with javac directly, bypassing Maven entirely.
# Delete this file once the investigation is done.

$ideRoot = "C:\Program Files\JetBrains\RustRover 2024.2.3"
$javac = "C:\Program Files\Java\jdk-26.0.2.1\bin\javac.exe"
$cpJar = Join-Path $PSScriptRoot "ide-classpath.jar"

Add-Type -AssemblyName System.IO.Compression.FileSystem

Write-Host "Scanning every jar under $ideRoot ..."
$scanned = 0
Get-ChildItem -Path $ideRoot -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object {
    $scanned++
    $zip = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
    try {
        $hits = @($zip.Entries | Where-Object {
            $_.FullName -like "com/intellij/credentialStore/*" -or
            $_.FullName -like "*PasswordSafe*"
        })
        if ($hits.Count -gt 0) {
            Write-Host ""
            Write-Host "== $($_.FullName)"
            foreach ($hit in $hits) {
                Write-Host "   $($hit.FullName)"
            }
        }
    } finally {
        $zip.Dispose()
    }
}
Write-Host ""
Write-Host "Scan complete: $scanned jar(s) scanned."

Write-Host ""
Write-Host "javac test against $cpJar (Maven bypassed) ..."
$testDir = Join-Path $env:TEMP "antiviber-javac-test"
New-Item -ItemType Directory -Path $testDir -Force | Out-Null
Set-Content -Path (Join-Path $testDir "T.java") -Value "import com.intellij.credentialStore.PasswordSafe; class T {}"
& $javac -cp $cpJar (Join-Path $testDir "T.java")
if ($LASTEXITCODE -eq 0) {
    Write-Host "RESULT: javac CAN resolve PasswordSafe via ide-classpath.jar - the problem is in how Maven invokes javac."
} else {
    Write-Host "RESULT: javac CANNOT resolve PasswordSafe via ide-classpath.jar either - the class is missing from the discovered jars."
}
Remove-Item -Recurse -Force $testDir -ErrorAction SilentlyContinue
