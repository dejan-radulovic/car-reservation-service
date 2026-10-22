# End-to-end smoke test against a running instance (e.g. `docker compose up -d --build`).
# Walks the main flow over HTTP: find an available car, book it, prove the overlap is rejected and the
# reservation is private, cancel it. Works in Windows PowerShell 5.1 and PowerShell 7+.
#
# Usage: scripts\smoke-test.ps1 [-BaseUrl http://localhost:8080]
param(
    [string] $BaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' })
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
# HttpClient instead of Invoke-RestMethod: it returns 4xx responses instead of throwing, in every PowerShell version
$client = New-Object System.Net.Http.HttpClient

function Fail([string] $Description, $Response) {
    Write-Host "  FAIL  $Description" -ForegroundColor Red
    if ($Response -and $Response.Body) { Write-Host $Response.Body }
    exit 1
}

function Send([string] $Method, [string] $Path, $Json) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), "$BaseUrl$Path"
    if ($null -ne $Json) {
        $body = $Json | ConvertTo-Json -Compress
        $request.Content = New-Object System.Net.Http.StringContent $body, ([System.Text.Encoding]::UTF8), 'application/json'
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $data = $null
    if ($response.Content.Headers.ContentType -and $response.Content.Headers.ContentType.MediaType -like '*json') {
        $data = $text | ConvertFrom-Json
    }
    [pscustomobject] @{ Status = [int] $response.StatusCode; Body = $text; Data = $data; Location = $response.Headers.Location }
}

function Expect([int] $Status, [string] $Description, [string] $Method, [string] $Path, $Json = $null) {
    try { $response = Send $Method $Path $Json } catch { Fail "$Description (no response: $($_.Exception.Message))" }
    if ($response.Status -ne $Status) { Fail "$Description`: expected $Status, got $($response.Status)" $response }
    Write-Host "  ok    $Description"
    $response
}

function Check([bool] $Condition, [string] $Description, $Response) {
    if (-not $Condition) { Fail $Description $Response }
}

function Iso([datetime] $Time) { $Time.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'") }

Write-Host "Smoke test against $BaseUrl"

for ($i = 0; $i -lt 60; $i++) {
    try { if ((Send GET /actuator/health/readiness).Status -eq 200) { break } } catch { }
    Start-Sleep -Seconds 2
}
$null = Expect 200 'service is ready' GET /actuator/health/readiness

# a random 3-hour slot 30-60 days ahead, so reruns against the same database do not collide
$now = [datetime]::UtcNow
$start = (New-Object DateTime $now.Year, $now.Month, $now.Day, $now.Hour, 0, 0, ([DateTimeKind]::Utc)).AddHours(24 * 30 + (Get-Random -Maximum (24 * 30)))
$from = Iso $start
$to = Iso $start.AddHours(3)
$user = [guid]::NewGuid().ToString()

$null = Expect 200 'fleet listing' GET '/api/v1/cars?size=1'

$available = Expect 200 "availability for $from .. $to" GET "/api/v1/cars/availability?from=$from&to=$to&size=1"
Check ($available.Data.items.Count -eq 1) 'an available car is returned' $available
$car = $available.Data.items[0].id

$booking = @{ carId = $car; userId = $user; startTime = $from; endTime = $to }
$created = Expect 201 "reserve car $car" POST /api/v1/reservations $booking
$reservation = $created.Data.id
Check ("$($created.Location)" -like "*/api/v1/reservations/$reservation*") 'Location header points to the reservation' $created

$conflict = Expect 409 'overlapping reservation is rejected' POST /api/v1/reservations `
    @{ carId = $car; userId = [guid]::NewGuid().ToString(); startTime = $from; endTime = $to }
Check ($conflict.Data.code -eq 'RESERVATION_CONFLICT') 'conflict code is RESERVATION_CONFLICT' $conflict

$after = Expect 200 'availability no longer offers the car' GET "/api/v1/cars/availability?from=$from&to=$to&size=1"
Check ($after.Data.items[0].id -ne $car) 'booked car is no longer listed as available' $after

$fetched = Expect 200 'owner reads the reservation' GET "/api/v1/reservations/$reservation`?userId=$user"
Check ($fetched.Data.status -eq 'CONFIRMED') 'status is CONFIRMED' $fetched
$null = Expect 404 'another user cannot see it' GET "/api/v1/reservations/$reservation`?userId=$([guid]::NewGuid())"

$cancelled = Expect 200 'owner cancels the reservation' POST "/api/v1/reservations/$reservation/cancel" @{ userId = $user }
Check ($cancelled.Data.status -eq 'CANCELLED') 'status is CANCELLED' $cancelled

$null = Expect 422 'a 1-hour slot is rejected' POST /api/v1/reservations `
    @{ carId = $car; userId = $user; startTime = $from; endTime = (Iso $start.AddHours(1)) }

$null = Expect 200 'Swagger UI is served' GET /swagger-ui/index.html
$null = Expect 200 'OpenAPI spec is served' GET /openapi/car-reservation-api.yaml

Write-Host 'Smoke test passed.' -ForegroundColor Green
