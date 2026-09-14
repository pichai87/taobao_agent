param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'analyst',
    [string]$Password = 'local-learning-only'
)
$ErrorActionPreference = 'Stop'
# 防止默认学习密码被脚本意外发往外部站点。
$uri = [Uri]$BaseUrl
if (-not $uri.IsLoopback) { throw '此学习脚本只允许访问本机服务。' }
$bytes = [Text.Encoding]::UTF8.GetBytes("${Username}:${Password}")
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String($bytes) }
$csrf = Invoke-RestMethod "$BaseUrl/api/csrf" -Headers $headers -SessionVariable agentSession
$headers[$csrf.headerName] = $csrf.token
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$body = @{
    question = 'GMV 为什么下降'
    date = '2026-09-12'
    compareDate = '2026-09-11'
    metric = 'GMV'
    reviewRequired = $false
} | ConvertTo-Json
$run = Invoke-RestMethod "$BaseUrl/api/runs" -Method Post -Headers $headers -WebSession $agentSession -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ($run.status -in @('RUNNING','QUEUED')) {
    if ([DateTime]::UtcNow -gt $deadline) { throw "任务超时：$($run.id)" }
    Start-Sleep -Milliseconds 200
    $run = Invoke-RestMethod "$BaseUrl/api/runs/$($run.id)" -Headers $headers -WebSession $agentSession
}
if ($run.status -ne 'SUCCEEDED') { throw "分析失败：$($run.errorCode)" }
if ($run.report.data.current.gmv -ne 2600 -or $run.report.data.previous.gmv -ne 3000) { throw 'GMV 验证失败' }
$run | ConvertTo-Json -Depth 12

