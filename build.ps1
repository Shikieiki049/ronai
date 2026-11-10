param([string[]]$Tasks = @('build'))
$root = $PSScriptRoot
$env:JAVA_HOME = Join-Path $root 'toolchain\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'
New-Item -ItemType Directory -Force (Join-Path $root 'tmp') | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$root\tmp -Djava.net.preferIPv4Stack=true"
Set-Location $root
& (Join-Path $root 'toolchain\gradle-7.6.5\bin\gradle.bat') --offline '-Dorg.gradle.jvmargs=-Xmx3G' @Tasks --console plain
exit $LASTEXITCODE
