Add-Type -AssemblyName System.Windows.Forms
$url = "file:///" + (Resolve-Path "static/index.html").Path.Replace("\","/")
$sync = [Windows.Threading.DispatcherSynchronizationContext]::Create()
$f = New-Object System.Windows.Forms.Form
$f.Width = 500; $f.Height = 800; $f.Visible = $false
$wb = New-Object System.Windows.Forms.WebBrowser
$wb.Dock = "Fill"
$f.Controls.Add($wb)
$wb.Navigate($url)
$docReady = $false
$wb.DocumentCompleted | ForEach-Object { $script = $_ }
while(-not $docReady){ [System.Windows.Forms.Application]::DoEvents() }
Start-Sleep -Milliseconds 1500
$doc = $wb.Document
Write-Output ("unlockPhone defined: " + ($doc.InvokeScript("eval",("typeof window.unlockPhone")) ))
Start-Sleep -Seconds 1
$done = $false
$wb.Document.parentWindow.execScript("document.getElementById('device-lock').click(); window.unlockPhone && window.unlockPhone();")
Start-Sleep -Seconds 1
Write-Output ("is-unlocked class: " + ($doc.body.GetAttribute("className")))
Write-Output ("lock hidden: " + ($doc.getElementById("device-lock").hidden))
$f.Close()
