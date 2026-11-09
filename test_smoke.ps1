# STL Generator v2 smoke test (ASCII-only script)
chcp 65001 | Out-Null
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$root = (Get-Location).Path
$scad1 = (Get-ChildItem -Path $root -Filter *.scad | Where-Object { $_.Name -notin @('sample.scad', 'repro.scad') } | Select-Object -First 1).FullName
$scad2 = Join-Path $root 'sample.scad'
"TARGET1: $scad1"

$p = Start-Process -FilePath 'java' -ArgumentList 'StlGenerator' -WorkingDirectory $root -PassThru -WindowStyle Hidden -RedirectStandardOutput 'server_out.log' -RedirectStandardError 'server_err.log'
Start-Sleep -Seconds 5
"SERVER PID: $($p.Id)"
if ($p.HasExited) { "SERVER EXITED EARLY"; Get-Content server_err.log; exit 1 }

function Poll-Job([string]$id, [int]$maxSec) {
  $deadline = (Get-Date).AddSeconds($maxSec)
  while ((Get-Date) -lt $deadline) {
    $j = Invoke-RestMethod -Uri ('http://localhost:8765/api/job?id=' + [uri]::EscapeDataString($id)) -TimeoutSec 30
    if ($j.status -ne 'running') { return $j }
    Start-Sleep -Milliseconds 800
  }
  throw "JOB TIMEOUT: $id"
}

# 1. UI + env
$ui = Invoke-WebRequest -Uri 'http://localhost:8765/' -UseBasicParsing
"UI: status=$($ui.StatusCode) bytes=$($ui.RawContentLength)"
$envj = Invoke-RestMethod -Uri 'http://localhost:8765/api/env'
"ENV: openscad=$($envj.openscad)"

# 2. load real file (part mode, 28 units)
$b = 'path=' + [uri]::EscapeDataString($scad1)
$j1 = Invoke-RestMethod -Uri 'http://localhost:8765/api/load' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $b
"LOAD-REAL: mode=$($j1.mode) units=$($j1.units.Count)"

# 3. async export: PART merge (03_gidung + 02_choseok)
$outDir = Join-Path $root 'out_test'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$b = 'scad=' + [uri]::EscapeDataString($scad1) + '&folder=' + [uri]::EscapeDataString($outDir) + '&mode=merge&kind=part&units=03_gidung:-1,02_choseok:-1&name='
$st = Invoke-RestMethod -Uri 'http://localhost:8765/api/export' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $b -TimeoutSec 60
"EXPORT1 START: ok=$($st.ok) jobId=$($st.jobId)"
$j4 = Poll-Job $st.jobId 600
"EXPORT1 DONE: status=$($j4.status) done=$($j4.done)/$($j4.total) allOk=$($j4.allOk)"
$j4.results | ForEach-Object { "   result: [$($_.name)] ok=$($_.ok) size=$($_.size)" }

# 4. IDX enumeration: 02_choseok (count=4) split+enum -> idx0..idx3
$b = 'scad=' + [uri]::EscapeDataString($scad1) + '&folder=' + [uri]::EscapeDataString($outDir) + '&mode=split&kind=part&units=02_choseok:-1&name=&enum=1'
$st = Invoke-RestMethod -Uri 'http://localhost:8765/api/export' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $b -TimeoutSec 60
$j5 = Poll-Job $st.jobId 900
$enumFiles = ($j5.results | Where-Object { $_.name -like '02_choseok_idx*' }).Count
"EXPORT2 ENUM: status=$($j5.status) total=$($j5.total) enumFiles=$enumFiles (expect 4)"

# 5. preview API (merged STL)
$merged = Get-ChildItem -Path $outDir -Filter '*.stl' | Where-Object { $_.Length -gt 100000 } | Sort-Object Length -Descending | Select-Object -First 1
if ($merged) {
  $pv = Invoke-WebRequest -Uri ('http://localhost:8765/api/preview?path=' + [uri]::EscapeDataString($merged.FullName)) -UseBasicParsing
  "PREVIEW: status=$($pv.StatusCode) bytes=$($pv.RawContentLength) (file=$($merged.Length))"
} else { "PREVIEW: merged file missing" }

# 6. settings (recent folder recorded by export)
$sj = Invoke-RestMethod -Uri 'http://localhost:8765/api/settings'
"SETTINGS: scads=$($sj.scads.Count) folders=$($sj.folders.Count)"

# 7. cancel: enumerate 20_buyeon (64 copies) then cancel immediately
$b = 'scad=' + [uri]::EscapeDataString($scad1) + '&folder=' + [uri]::EscapeDataString($outDir) + '&mode=split&kind=part&units=20_buyeon:-1&name=&enum=1'
$st = Invoke-RestMethod -Uri 'http://localhost:8765/api/export' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $b -TimeoutSec 60
Start-Sleep -Seconds 2
$null = Invoke-RestMethod -Uri 'http://localhost:8765/api/cancel' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body ('id=' + [uri]::EscapeDataString($st.jobId)) -TimeoutSec 30
$j7 = Poll-Job $st.jobId 300
"CANCEL: status=$($j7.status) done=$($j7.done)/$($j7.total)"

# 8. module mode quick check (async)
$b = 'scad=' + [uri]::EscapeDataString($scad2) + '&folder=' + [uri]::EscapeDataString($outDir) + '&mode=merge&kind=module&units=base:-1,pillar:-1,ring:-1&name=sample_all'
$st = Invoke-RestMethod -Uri 'http://localhost:8765/api/export' -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $b -TimeoutSec 60
$j8 = Poll-Job $st.jobId 300
$mergedRow = $j8.results | Where-Object { $_.name -eq 'sample_all.stl' } | Select-Object -First 1
"EXPORT3 MODULE-MERGE: status=$($j8.status) mergedOk=$($mergedRow.ok) size=$($mergedRow.size)"

Stop-Process -Id $p.Id -Force
"SERVER STOPPED"
"DONE"
