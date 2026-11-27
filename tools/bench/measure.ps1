
param([string]$exe,[string]$argline,[string]$cwd,[switch]$quiet)
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe; $psi.Arguments = $argline; $psi.WorkingDirectory = $cwd
$psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)
$outTask = $p.StandardOutput.ReadToEndAsync(); $errTask = $p.StandardError.ReadToEndAsync()
$peak = 0L; $sw = [Diagnostics.Stopwatch]::StartNew()
while (-not $p.HasExited) { try { $p.Refresh(); if ($p.WorkingSet64 -gt $peak) { $peak = $p.WorkingSet64 } } catch {}; Start-Sleep -Milliseconds 20 }
$p.WaitForExit(); $sw.Stop()
if ($quiet) {
  [pscustomobject]@{ ms = $sw.ElapsedMilliseconds; peak = $peak; exit = $p.ExitCode } | ConvertTo-Json -Compress
} else {
  [pscustomobject]@{ ms = $sw.ElapsedMilliseconds; peak = $peak; exit = $p.ExitCode; out = $outTask.Result; err = $errTask.Result } | ConvertTo-Json -Compress
}
