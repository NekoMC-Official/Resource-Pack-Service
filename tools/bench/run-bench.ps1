param([string]$Request, [string]$Output, [string]$Exe, [int]$Runs = 3)
$ErrorActionPreference = 'Stop'
for ($i = 0; $i -lt $Runs; $i++) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Exe
  $psi.Arguments = "merge --request `"$Request`" --output `"$Output`""
  $psi.WorkingDirectory = (Get-Location).Path
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.UseShellExecute = $false
  $p = [System.Diagnostics.Process]::Start($psi)
  $outTask = $p.StandardOutput.ReadToEndAsync()
  $errTask = $p.StandardError.ReadToEndAsync()
  $peak = 0L
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while (-not $p.HasExited) {
    try { $p.Refresh(); if ($p.WorkingSet64 -gt $peak) { $peak = $p.WorkingSet64 } } catch {}
    Start-Sleep -Milliseconds 20
  }
  $p.WaitForExit()
  $sw.Stop()
  $out = $outTask.Result
  $report = $null
  try { $report = $out | ConvertFrom-Json } catch {}
  "RUN $i wall=$($sw.ElapsedMilliseconds) peak=$peak exit=$($p.ExitCode) millis=$($report.millis) bytes=$($report.bytes) entries=$($report.entries) sha1=$($report.sha1)"
  if ($p.ExitCode -ne 0) { "ERR " + $errTask.Result }
}
