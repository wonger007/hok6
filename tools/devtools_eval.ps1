# Runs one JavaScript expression in Hok6's writing practice page through Chrome DevTools, on the Windows side, for
# tools/device_check.py when the device is reached through Windows' adb (tools/adbw): the port adb forwards is on
# Windows' 127.0.0.1, which WSL can't reach without mirrored networking. Nothing listens on the network.
# Usage: powershell -File devtools_eval.ps1 PORT EXPRESSION_FILE TIMEOUT_SECONDS  (the expression is read as UTF-8)
# Prints the DevTools reply (JSON) for the expression.
param([int]$Port, [string]$ExpressionFile, [int]$Timeout = 30)
$ErrorActionPreference = 'Stop'
$expression = [IO.File]::ReadAllText($ExpressionFile, [Text.Encoding]::UTF8)
$pages = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 10
$page = $pages | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cancel = New-Object System.Threading.CancellationTokenSource ([TimeSpan]::FromSeconds($Timeout))
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $cancel.Token).Wait()
$message = @{ id = 1; method = 'Runtime.evaluate'; params = @{ expression = $expression; awaitPromise = $true; returnByValue = $true } } |
    ConvertTo-Json -Depth 5 -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($message)
$ws.SendAsync((New-Object ArraySegment[byte] (, $bytes)), 'Text', $true, $cancel.Token).Wait()
$buffer = New-Object byte[] 65536
while ($true) {
    $stream = New-Object IO.MemoryStream
    do {
        $part = $ws.ReceiveAsync((New-Object ArraySegment[byte] (, $buffer)), $cancel.Token)
        $part.Wait()
        $stream.Write($buffer, 0, $part.Result.Count)
    } while (-not $part.Result.EndOfMessage)
    $text = [Text.Encoding]::UTF8.GetString($stream.ToArray())
    if ($text -match '"id"\s*:\s*1[,}]') {
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        [Console]::Out.Write($text)
        break
    }
}
$ws.Dispose()
