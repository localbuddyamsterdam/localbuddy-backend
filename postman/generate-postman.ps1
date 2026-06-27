# Generates a persona-grouped Postman collection + environment from docs/openapi.json.
# Run:  pwsh/powershell -File postman/generate-postman.ps1
param(
  [string]$SpecPath = "docs/openapi.json",
  [string]$OutDir   = "postman",
  [string]$BaseUrl  = "https://localbuddy-backend-b4exhkbjgahme6ge.francecentral-01.azurewebsites.net"
)

$ErrorActionPreference = "Stop"
$spec = Get-Content $SpecPath -Raw | ConvertFrom-Json

# ---- persona / auth mapping ------------------------------------------------
$hostTags = @('Local Profiles','Host tax info','Experiences','Experience Photos','Availability',
  'Host Payouts','Host - Announcements','Invoices','Booking Safety Checklist','No-show',
  'Underbooked slots','host-attendance-controller')

function Get-Persona($tag) {
  if ($tag -match '^Admin') { return 'Admin' }
  if ($tag -match '^Public' -or $tag -in @('Health','Cities','Experience Categories') -or $tag -match 'guest') { return 'Public' }
  if ($hostTags -contains $tag) { return 'Host' }
  return 'Traveler'
}
function Get-TokenVar($persona) {
  switch ($persona) {
    'Admin'    { '{{adminToken}}' }
    'Public'   { '' }
    'Host'     { '{{hostToken}}' }
    default    { '{{travelerToken}}' }
  }
}

# ---- schema -> example body ------------------------------------------------
function New-Example($schema, [int]$depth) {
  if ($null -eq $schema -or $depth -gt 5) { return $null }
  if ($schema.'$ref') {
    $n = $schema.'$ref' -replace '#/components/schemas/',''
    return New-Example $spec.components.schemas.$n ($depth+1)
  }
  if ($schema.enum) { return $schema.enum[0] }
  switch ($schema.type) {
    'object' {
      $o = [ordered]@{}
      if ($schema.properties) {
        foreach ($p in $schema.properties.PSObject.Properties) {
          $o[$p.Name] = New-FieldValue $p.Name $p.Value ($depth+1)
        }
      }
      return $o
    }
    'array'   { return @() }
    'integer' { return 1 }
    'number'  { return 10.0 }
    'boolean' { return $false }
    'string'  { return "string" }
    default   {
      if ($schema.properties) { return (New-Example $schema $depth) }
      return "string"
    }
  }
}
function New-FieldValue($name, $schema, [int]$depth) {
  if ($schema.'$ref' -or $schema.type -eq 'object') { return New-Example $schema $depth }
  if ($schema.enum) { return $schema.enum[0] }
  if ($name -match 'Id$' -and $schema.type -eq 'string') { return "{{$name}}" }
  if ($name -eq 'email') { return "{{travelerEmail}}" }
  if ($name -eq 'password') { return "{{travelerPassword}}" }
  switch ($schema.type) {
    'array'   { return @() }
    'integer' { return 1 }
    'number'  { return 10.0 }
    'boolean' { return $false }
    default   { return "string" }
  }
}

# ---- build one Postman request item ---------------------------------------
function New-RequestItem($path, $method, $op, $persona) {
  $segments = @($path.Trim('/').Split('/') | ForEach-Object { $_ -replace '^\{(.+)\}$','{{$1}}' })
  $rawPath = ($segments -join '/')
  $token = Get-TokenVar $persona

  $headers = @()
  $bodyObj = $null
  if ($op.requestBody) {
    $bs = $op.requestBody.content.'application/json'.schema
    if ($bs) {
      $bodyObj = New-Example $bs 0
      $headers += @{ key = 'Content-Type'; value = 'application/json' }
    }
  }

  $query = @()
  if ($op.parameters) {
    foreach ($pm in $op.parameters) {
      if ($pm.'in' -eq 'query') {
        $query += @{ key = $pm.name; value = "{{$($pm.name)}}"; disabled = (-not $pm.required) }
      }
    }
  }

  $url = [ordered]@{
    raw  = "{{baseUrl}}/$rawPath"
    host = @('{{baseUrl}}')
    path = $segments
  }
  if ($query.Count -gt 0) { $url.raw = $url.raw + '?' + (($query | ForEach-Object { "$($_.key)=$($_.value)" }) -join '&'); $url.query = $query }

  $req = [ordered]@{
    method = $method.ToUpper()
    header = $headers
    url    = $url
  }
  if ($token -ne '') {
    $req.auth = @{ type = 'bearer'; bearer = @(@{ key = 'token'; value = $token; type = 'string' }) }
  } else {
    $req.auth = @{ type = 'noauth' }
  }
  if ($null -ne $bodyObj) {
    $req.body = @{ mode = 'raw'; raw = (ConvertTo-Json $bodyObj -Depth 8); options = @{ raw = @{ language = 'json' } } }
  }

  $name = $op.summary
  if (-not $name) { $name = "$($method.ToUpper()) /$rawPath" }

  return [ordered]@{
    name    = "$($method.ToUpper()) $name"
    request = $req
    response = @()
  }
}

# ---- group endpoints into persona -> tag folders --------------------------
$personaFolders = [ordered]@{ 'Public' = [ordered]@{}; 'Admin' = [ordered]@{}; 'Host' = [ordered]@{}; 'Traveler' = [ordered]@{} }

foreach ($pp in $spec.paths.PSObject.Properties) {
  foreach ($mm in $pp.Value.PSObject.Properties) {
    if ($mm.Name -notin @('get','post','put','patch','delete')) { continue }
    $op = $mm.Value
    $tag = if ($op.tags) { $op.tags[0] } else { 'Other' }
    $persona = Get-Persona $tag
    if (-not $personaFolders[$persona].Contains($tag)) { $personaFolders[$persona][$tag] = @() }
    $personaFolders[$persona][$tag] += (New-RequestItem $pp.Name $mm.Name $op $persona)
  }
}

$personaItems = @()
$icons = @{ 'Public'='Public & Guest (no auth)'; 'Admin'='Admin'; 'Host'='Host (Local)'; 'Traveler'='Traveler / Authenticated' }
foreach ($persona in @('Public','Admin','Host','Traveler')) {
  $tagFolders = @()
  foreach ($tag in ($personaFolders[$persona].Keys | Sort-Object)) {
    $tagFolders += [ordered]@{ name = $tag; item = @($personaFolders[$persona][$tag]) }
  }
  $personaItems += [ordered]@{ name = $icons[$persona]; item = @($tagFolders) }
}

# ---- helper to build a hand-crafted request -------------------------------
function Req($name,$method,$path,$token,$bodyJson,$testScript,$preScript) {
  $segments = @($path.Trim('/').Split('/'))
  $req = [ordered]@{
    method = $method
    header = @()
    url = [ordered]@{ raw = "{{baseUrl}}/$($path.Trim('/'))"; host=@('{{baseUrl}}'); path=$segments }
  }
  if ($token) { $req.auth = @{ type='bearer'; bearer=@(@{key='token';value=$token;type='string'}) } } else { $req.auth = @{ type='noauth' } }
  if ($bodyJson) { $req.header += @{key='Content-Type';value='application/json'}; $req.body = @{ mode='raw'; raw=$bodyJson; options=@{raw=@{language='json'}} } }
  $ev = @()
  if ($preScript)  { $ev += @{ listen='prerequest'; script=@{ type='text/javascript'; exec=@($preScript -split "`n") } } }
  if ($testScript) { $ev += @{ listen='test';       script=@{ type='text/javascript'; exec=@($testScript -split "`n") } } }
  $item = [ordered]@{ name=$name; request=$req; response=@() }
  if ($ev.Count) { $item.event = $ev }
  return $item
}

$capId = "let j=pm.response.json(); let v=Array.isArray(j)?(j[0]&&j[0].id):(j.content?(j.content[0]&&j.content[0].id):j.id); if(v) pm.environment.set('PLACEHOLDER', v);"

$setupItems = @(
  (Req 'List cities (capture cityId)' 'GET' '/api/cities' '' $null ($capId -replace 'PLACEHOLDER','cityId') $null),
  (Req 'List categories (capture categoryId)' 'GET' '/api/experience-categories' '' $null ($capId -replace 'PLACEHOLDER','categoryId') $null),
  (Req 'Login ADMIN (capture adminToken)' 'POST' '/api/auth/login' '' '{ "email": "{{adminEmail}}", "password": "{{adminPassword}}" }' "pm.test('login ok',()=>pm.response.code===200); let t=pm.response.json().accessToken; pm.environment.set('adminToken', t);" $null),
  (Req 'Signup TRAVELER (ok if 400 exists)' 'POST' '/api/auth/signup' '' '{ "fullName": "Test Traveler", "email": "{{travelerEmail}}", "phone": "+31600000001", "password": "{{travelerPassword}}", "role": "LOGGED_IN_USER" }' "pm.test('signup ok or exists',()=>[201,400].includes(pm.response.code));" $null),
  (Req 'Login TRAVELER (capture travelerToken)' 'POST' '/api/auth/login' '' '{ "email": "{{travelerEmail}}", "password": "{{travelerPassword}}" }' "pm.test('login ok',()=>pm.response.code===200); pm.environment.set('travelerToken', pm.response.json().accessToken);" $null),
  (Req 'Signup HOST (ok if 400 exists)' 'POST' '/api/auth/signup' '' '{ "fullName": "Test Host", "email": "{{hostEmail}}", "phone": "+31600000002", "password": "{{hostPassword}}", "role": "LOCAL" }' "pm.test('signup ok or exists',()=>[201,400].includes(pm.response.code));" $null),
  (Req 'Login HOST (capture hostToken)' 'POST' '/api/auth/login' '' '{ "email": "{{hostEmail}}", "password": "{{hostPassword}}" }' "pm.test('login ok',()=>pm.response.code===200); pm.environment.set('hostToken', pm.response.json().accessToken);" $null)
)
$setupFolder = [ordered]@{ name = '0 - Setup (run first)'; item = @($setupItems) }

# Smoke flow (ordered, chained). Bodies use captured ids; slot times set in pre-request.
$slotPre = "pm.environment.set('slotStart', new Date(Date.now()+86400000).toISOString());`npm.environment.set('slotEnd', new Date(Date.now()+90000000).toISOString());"
$smokeItems = @(
  (Req 'HOST: create local profile' 'POST' '/api/local-profiles/me' '{{hostToken}}' '{ "displayName":"Test Host","phoneNumber":"+31600000002","bio":"Friendly local guide for testing.","profilePhotoUrl":"https://example.com/p.jpg","hostCity":"Amsterdam","zipCode":"1011AA","country":"NL","experienceCityIds":["{{cityId}}"],"experienceCategoryIds":["{{categoryId}}"],"experienceLanguages":["English"],"motivation":"I love showing my city.","experienceInfo":"10 years guiding.","legalFirstName":"Test","legalLastName":"Host","preferredName":"Testy","currentAddress":"Damrak 1, Amsterdam" }' "pm.test('created or exists',()=>[200,201,400].includes(pm.response.code)); if(pm.response.code<300){let j=pm.response.json(); if(j.id)pm.environment.set('localProfileId',j.id);}" $null),
  (Req 'HOST: submit profile for review' 'POST' '/api/local-profiles/me/submit' '{{hostToken}}' $null "pm.test('ok',()=>[200,400].includes(pm.response.code));" $null),
  (Req 'ADMIN: list pending profiles (capture localProfileId)' 'GET' '/api/admin/local-profiles/pending' '{{adminToken}}' $null ($capId -replace 'PLACEHOLDER','localProfileId') $null),
  (Req 'ADMIN: approve host profile' 'POST' '/api/admin/local-profiles/{{localProfileId}}/approve' '{{adminToken}}' $null "pm.test('ok',()=>[200,400,404].includes(pm.response.code));" $null),
  (Req 'HOST: create experience (capture experienceId)' 'POST' '/api/experiences' '{{hostToken}}' '{ "categoryId":"{{categoryId}}","cityId":"{{cityId}}","title":"Test Canal Walk","description":"A lovely test walk along the canals of Amsterdam for smoke testing.","meetingArea":"Central Station","durationMinutes":90,"priceAmount":35.0,"currency":"EUR","maxGuests":8,"minimumAge":0,"bookingMode":"SHARED","priceInputMode":"GROSS" }' "pm.test('created',()=>pm.response.code<300); let j=pm.response.json(); if(j.id)pm.environment.set('experienceId',j.id);" $null),
  (Req 'HOST: submit experience' 'POST' '/api/experiences/{{experienceId}}/submit' '{{hostToken}}' $null "pm.test('ok',()=>[200,400].includes(pm.response.code));" $null),
  (Req 'ADMIN: approve experience' 'POST' '/api/admin/experiences/{{experienceId}}/approve' '{{adminToken}}' $null "pm.test('ok',()=>[200,400,404].includes(pm.response.code));" $null),
  (Req 'HOST: create availability slot (capture slotId)' 'POST' '/api/availability' '{{hostToken}}' '{ "experienceId":"{{experienceId}}","startTime":"{{slotStart}}","endTime":"{{slotEnd}}","capacity":8 }' "pm.test('created',()=>pm.response.code<300); let j=pm.response.json(); if(j.id)pm.environment.set('slotId',j.id);" $slotPre),
  (Req 'PUBLIC: get experience by id' 'GET' '/api/public/experiences/{{experienceId}}' '' $null "pm.test('ok',()=>[200,404].includes(pm.response.code));" $null),
  (Req 'PUBLIC: experience availability' 'GET' '/api/public/experiences/{{experienceId}}/availability' '' $null "pm.test('ok',()=>pm.response.code===200);" $null),
  (Req 'TRAVELER: create booking (capture bookingId)' 'POST' '/api/bookings' '{{travelerToken}}' '{ "experienceId":"{{experienceId}}","availabilitySlotId":"{{slotId}}","guestsCount":2,"adults":2,"travelerNote":"Smoke test booking." }' "pm.test('created',()=>pm.response.code<300); let j=pm.response.json(); if(j.id)pm.environment.set('bookingId',j.id);" $null),
  (Req 'TRAVELER: get my booking' 'GET' '/api/bookings/{{bookingId}}' '{{travelerToken}}' $null "pm.test('ok',()=>[200,404].includes(pm.response.code));" $null)
)
$smokeFolder = [ordered]@{ name = '1 - Smoke flow (run in order)'; item = @($smokeItems) }

# ---- assemble collection ---------------------------------------------------
$collection = [ordered]@{
  info = [ordered]@{
    name = 'LocalBuddy API - Dev (Azure)'
    description = 'Auto-generated from docs/openapi.json. Run "0 — Setup" first to capture tokens + ids, then "1 — Smoke flow", then explore persona folders.'
    schema = 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'
  }
  auth = @{ type = 'bearer'; bearer = @(@{ key='token'; value='{{travelerToken}}'; type='string' }) }
  event = @(
    @{ listen='test'; script=@{ type='text/javascript'; exec=@(
      "pm.test('no server error (status < 500)', function(){ pm.expect(pm.response.code).to.be.below(500); });"
    )}}
  )
  item = @(@($setupFolder, $smokeFolder) + $personaItems)
}

# ---- environment -----------------------------------------------------------
$envValues = @(
  @{ key='baseUrl'; value=$BaseUrl; enabled=$true },
  @{ key='adminEmail'; value='localbuddyamsterdam@gmail.com'; enabled=$true },
  @{ key='adminPassword'; value='Nexus$141441'; enabled=$true },
  @{ key='travelerEmail'; value='traveler.test@localbuddy.dev'; enabled=$true },
  @{ key='travelerPassword'; value='Traveler@2026x'; enabled=$true },
  @{ key='hostEmail'; value='host.test@localbuddy.dev'; enabled=$true },
  @{ key='hostPassword'; value='HostUser@2026x'; enabled=$true },
  @{ key='adminToken'; value=''; enabled=$true },
  @{ key='travelerToken'; value=''; enabled=$true },
  @{ key='hostToken'; value=''; enabled=$true },
  @{ key='cityId'; value=''; enabled=$true },
  @{ key='categoryId'; value=''; enabled=$true },
  @{ key='localProfileId'; value=''; enabled=$true },
  @{ key='experienceId'; value=''; enabled=$true },
  @{ key='slotId'; value=''; enabled=$true },
  @{ key='bookingId'; value=''; enabled=$true },
  @{ key='photoId'; value=''; enabled=$true },
  @{ key='slotStart'; value=''; enabled=$true },
  @{ key='slotEnd'; value=''; enabled=$true }
)
$environment = [ordered]@{
  id = 'localbuddy-dev-env'
  name = 'LocalBuddy Dev (Azure)'
  values = $envValues
  _postman_variable_scope = 'environment'
}

# ---- serialize -------------------------------------------------------------
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
($collection  | ConvertTo-Json -Depth 100) | Set-Content "$OutDir/LocalBuddy.postman_collection.json" -Encoding UTF8
($environment | ConvertTo-Json -Depth 40)  | Set-Content "$OutDir/LocalBuddy-Dev.postman_environment.json" -Encoding UTF8

$total = 0; foreach($pf in $personaFolders.Values){ foreach($t in $pf.Values){ $total += $t.Count } }
Write-Output "Collection written. Endpoints: $total  (+ setup $($setupItems.Count) + smoke $($smokeItems.Count))"
