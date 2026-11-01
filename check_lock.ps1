Add-Type -AssemblyName System.Windows.Forms
# Force the legacy WebBrowser control to use IE11 standards mode so ES6-ish syntax parses.
$emuKey = "HKCU:\Software\Microsoft\Internet Explorer\Main\FeatureControl\FEATURE_BROWSER_EMULATION"
if (-not (Test-Path $emuKey)) { New-Item -Path $emuKey -Force | Out-Null }
Set-ItemProperty -Path $emuKey -Name "powershell.exe" -Value 11000 -Type DWord
$wcKey = "HKCU:\Software\Microsoft\Internet Explorer\Main\FeatureControl\FEATURE_BEHAVIOR_BROWSER_EMULATION"
if (-not (Test-Path $wcKey)) { New-Item -Path $wcKey -Force | Out-Null }
Set-ItemProperty -Path $wcKey -Name "powershell.exe" -Value 11000 -Type DWord
$url = "file:///" + (Resolve-Path "static/index.html").Path.Replace("\","/")
Write-Output ("URL: " + $url)
$f = New-Object System.Windows.Forms.Form
$f.Width = 520; $f.Height = 840; $f.Visible = $false
$wb = New-Object System.Windows.Forms.WebBrowser
$wb.Dock = "Fill"
$f.Controls.Add($wb)
$wb.Navigate($url)
$sw = [Diagnostics.Stopwatch]::StartNew()
while($wb.ReadyState -ne [System.Windows.Forms.WebBrowserReadyState]::Complete -and $sw.ElapsedMilliseconds -lt 10000){ [System.Windows.Forms.Application]::DoEvents() }
Start-Sleep -Milliseconds 1000
$doc = $wb.Document
Write-Output ("doc null: " + ($doc -eq $null))
Write-Output ("unlockPhone type: " + ($doc.InvokeScript("eval",("typeof window.unlockPhone"))))
Write-Output ("lock element exists: " + ($doc.getElementById("device-lock") -ne $null))
Write-Output ("device element exists: " + ($doc.querySelector(".device") -ne $null))
# click the lock element (simulates tap / click)
$doc.getElementById("device-lock").click()
Start-Sleep -Milliseconds 600
# also call the global explicitly
$doc.parentWindow.InvokeScript("eval","if(window.unlockPhone) window.unlockPhone();")
Start-Sleep -Milliseconds 600
Write-Output ("after unlock, unlockPhone type: " + ($doc.InvokeScript("eval",("typeof window.unlockPhone"))))
Write-Output ("body is-unlocked class: " + ($doc.body.className))
$el = $doc.getElementById("device-lock")
Write-Output ("lock hidden after click: " + ($el -eq $null ? "removed" : $el.hidden))
$f.Close()

