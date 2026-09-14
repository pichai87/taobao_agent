param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'analyst',
    [string]$Password = 'local-learning-only'
)
$ErrorActionPreference = 'Stop'
$uri = [Uri]$BaseUrl
if (-not $uri.IsLoopback) {
    throw 'This learning script only sends credentials to a local server.'
}

$credentials = $Username + ':' + $Password
$bytes = [Text.Encoding]::UTF8.GetBytes($credentials)
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String($bytes) }
$csrf = Invoke-RestMethod ($BaseUrl + '/api/csrf') -Headers $headers -SessionVariable agentSession
$headers[$csrf.headerName] = $csrf.token
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$comparisonQuestion = 'GMV ' + [char]0x4E3A + [char]0x4EC0 + [char]0x4E48
$body = @{
    question = $comparisonQuestion
    date = '2026-09-12'
    compareDate = '2026-09-11'
    metric = 'GMV'
    reviewRequired = $false
} | ConvertTo-Json
$run = Invoke-RestMethod ($BaseUrl + '/api/runs') -Method Post -Headers $headers -WebSession $agentSession -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ($run.status -in @('RUNNING','QUEUED')) {
    if ([DateTime]::UtcNow -gt $deadline) { throw ('The run timed out: ' + $run.id) }
    Start-Sleep -Milliseconds 200
    $run = Invoke-RestMethod "$BaseUrl/api/runs/$($run.id)" -Headers $headers -WebSession $agentSession
}
if ($run.status -ne 'SUCCEEDED') { throw ('The run failed: ' + $run.errorCode) }
if ($run.report.data.current.gmv -ne 2600 -or $run.report.data.previous.gmv -ne 3000) { throw 'GMV verification failed.' }
$run | ConvertTo-Json -Depth 12
