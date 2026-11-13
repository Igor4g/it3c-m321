param(
    [ValidateSet('All', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7', 'S8')]
    [string]$Scenario = 'All'
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $projectRoot
$resultDirectory = Join-Path $projectRoot 'chat-service/target/acceptance'
New-Item -ItemType Directory -Force $resultDirectory | Out-Null
# Nur eigene alte Prüfartefakte entfernen; niemals Anwendungsdaten oder Volumes.
foreach ($artifactName in @('results.json', 'queue-samples.json', 'failure.txt')) {
    $artifact = Join-Path $resultDirectory $artifactName
    if (Test-Path -LiteralPath $artifact) { Remove-Item -LiteralPath $artifact }
}
$results = [Collections.Generic.List[object]]::new()
$pausedContainer = $null
$stoppedPersistence = $false
$queueSamples = [Collections.Generic.List[object]]::new()

# Native Befehle müssen ebenfalls einen Fehler liefern; PowerShell allein prüft das nicht.
function Invoke-Docker {
    param([string[]]$CommandArguments)
    $output = & docker @CommandArguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($CommandArguments -join ' ') failed: $LASTEXITCODE" }
    return ($output -join "`n")
}

# Ein Szenario gilt erst nach seinen Assertions als erfolgreich.
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Container-Adressen werden erneut aufgelöst, weil Skalierung sie ändern kann.
function Get-ChatInstances {
    $identifiers = Invoke-Docker @('compose', 'ps', '-q', 'chat-service')
    $instances = @()
    foreach ($identifier in ($identifiers -split "`n")) {
        if (-not $identifier.Trim()) { continue }
        $raw = Invoke-Docker @('inspect', $identifier)
        $container = @($raw | ConvertFrom-Json)[0]
        $instances += [pscustomobject]@{
            Id = $identifier.Trim()
            Address = $container.NetworkSettings.Networks.'chat-net'.IPAddress
        }
    }
    Assert-True ($instances.Count -gt 0) 'No chat-service containers found'
    return $instances
}

# Nur lesende Broker-Messung; kein basic.get und kein Verändern der Queues.
function Get-Queues {
    $raw = Invoke-Docker @('compose', 'exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q',
        'list_queues', 'name', 'consumers', 'messages_ready', 'messages_unacknowledged', 'arguments', '--formatter=json')
    # PowerShell 5.1 gibt das JSON-Array sonst als ein einziges Pipeline-Objekt weiter.
    $queues = $raw | ConvertFrom-Json
    foreach ($queue in $queues) { Write-Output $queue }
}

# Die Bindung bestimmt die Live-Queues, nicht eine Vermutung über ihren Zufallsnamen.
function Get-LiveQueues {
    $raw = Invoke-Docker @('compose', 'exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q',
        'list_bindings', 'source_name', 'destination_name', 'destination_kind', '--formatter=json')
    $names = @()
    foreach ($binding in ($raw | ConvertFrom-Json)) {
        if ($binding.source_name -eq 'chat.delivery' -and $binding.destination_kind -eq 'queue') {
            $names += $binding.destination_name
        }
    }
    return @(Get-Queues | Where-Object { $names -contains $_.name })
}

# Beide dauerhaften Queues müssen auch nach Skalierung und Überlauf existieren.
function Assert-PersistenceQueues {
    $names = @(Get-Queues | ForEach-Object { $_.name })
    Assert-True ($names -contains 'chat.persist') 'chat.persist missing'
    Assert-True ($names -contains 'chat.dlq') 'chat.dlq missing'
}

# Die AMQP-Typangabe bleibt erhalten, während wir den eigentlichen Grenzwert lesen.
function Get-QueueArgument {
    param($Queue, [string]$Name)
    # rabbitmqctl liefert AMQP-Tabellen als [Name, Typ, Wert], nicht als JSON-Objekt.
    foreach ($argument in $Queue.arguments) {
        if ($argument[0] -eq $Name) { return $argument[2] }
    }
    throw "Queue argument missing: $Name"
}

# Jeder Messpunkt prüft sowohl Konfiguration als auch tatsächlichen Rückstau.
function Measure-QueueLimit {
    $queues = @(Get-LiveQueues)
    Assert-True ($queues.Count -eq 2) 'S6 requires two live queues'
    foreach ($queue in $queues) {
        $maximumReady = Get-QueueArgument $queue 'x-max-length'
        $overflow = Get-QueueArgument $queue 'x-overflow'
        Assert-True ($maximumReady -eq 999) 'Live queue must limit ready messages to 999'
        Assert-True ($overflow -eq 'drop-head') 'Live overflow must drop the oldest copy'
        Assert-True ($queue.consumers -eq 1) 'Live queue must retain exactly one consumer'
        $total = [int]$queue.messages_ready + [int]$queue.messages_unacknowledged
        Assert-True ($total -le 1000) "Queue $($queue.name) exceeds 1000: $total"
        $queueSamples.Add([pscustomobject]@{
            At = (Get-Date).ToString('o'); Queue = $queue.name
            Ready = $queue.messages_ready; Unacknowledged = $queue.messages_unacknowledged; Total = $total
            Arguments = $queue.arguments
        })
    }
}

# SQL verwendet nur die vom eigenen Test erzeugte, validierte UUID als Raumfilter.
function Assert-StoredMessages {
    param($ClientResult)
    $roomId = [guid]::Parse($ClientResult.roomId).ToString()
    $sql = "SELECT id FROM message WHERE room_id = '$roomId' ORDER BY id;"
    $deadline = (Get-Date).AddSeconds(60)
    $postgresId = Invoke-Docker @('compose', 'ps', '-q', 'postgres')
    do {
        $health = Invoke-Docker @('inspect', '--format', '{{.State.Health.Status}}', $postgresId)
        if ($health -eq 'healthy') { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Assert-True ($health -eq 'healthy') 'S5: PostgreSQL did not become healthy after restart'
    do {
        $output = $sql | & docker compose exec -T postgres sh -c 'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
        if ($LASTEXITCODE -eq 0) {
            $actual = @($output | Where-Object { $_ -match '^[0-9a-f-]{36}$' } | Sort-Object)
            $expected = @($ClientResult.ids | Sort-Object)
            $differences = @(Compare-Object $expected $actual)
            if ($actual.Count -eq 100 -and $differences.Count -eq 0) { return }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw 'S5: the exact 100 IDs were not persisted within 60 seconds after restart'
}

# Dieser Prozess enthält nur den Prüfclient, ohne Host-Ports oder Docker-Socket.
function Invoke-Client {
    param([string]$Name, $Instances)
    $clientName = 'm321-check-' + [guid]::NewGuid().ToString('N')
    $secondAddress = $Instances[0].Address
    if ($Instances.Count -gt 1) { $secondAddress = $Instances[1].Address }
    $mount = $PSScriptRoot.Replace('\', '/') + ':/checks:ro'
    $arguments = @('run', '--rm', '-i', '--name', $clientName, '--network', 'chat-net', '-v',
        $mount, 'node:22-alpine', 'node', '/checks/live-client.mjs', $Name,
        $Instances[0].Address, $secondAddress)
    $quoted = foreach ($argument in $arguments) { '"' + $argument.Replace('"', '\"') + '"' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = 'docker.exe'
    $start.Arguments = $quoted -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    $errors = $process.StandardError.ReadToEndAsync()
    $deadline = (Get-Date).AddSeconds(200)
    $done = $null
    try {
        $reading = $process.StandardOutput.ReadLineAsync()
        while ((Get-Date) -lt $deadline) {
            if (-not $reading.Wait(200)) { continue }
            $line = $reading.Result
            if ($null -eq $line) { break }
            $event = $line | ConvertFrom-Json
            if ($event.event -eq 'ready') {
                if ($Name -eq 'S4') {
                    $before = @(Get-LiveQueues)
                    Assert-True ($before.Count -eq 2) 'S4: expected exactly two live queues'
                    foreach ($queue in $before) {
                        Assert-True ($queue.consumers -eq 1) 'S4: expected one consumer per live queue'
                    }
                    $watch = [Diagnostics.Stopwatch]::StartNew()
                    Invoke-Docker @('compose', 'up', '-d', '--scale', 'chat-service=1') | Out-Host
                    do {
                        $after = @(Get-LiveQueues)
                        if ($after.Count -eq 1) { break }
                        Start-Sleep -Milliseconds 200
                    } while ($watch.Elapsed.TotalSeconds -lt 30)
                    Assert-True ($after.Count -eq 1 -and $watch.Elapsed.TotalSeconds -le 30) 'S4: queue removal exceeded 30 seconds'
                    Assert-PersistenceQueues
                    $script:scaleMeasurement = [math]::Round($watch.Elapsed.TotalSeconds, 3)
                } elseif ($Name -eq 'S5') {
                    $script:stoppedPersistence = $true
                    Invoke-Docker @('compose', 'stop', 'batch-writer', 'postgres') | Out-Host
                } elseif ($Name -eq 'S6') {
                    $script:pausedContainer = $Instances[0].Id
                    Invoke-Docker @('pause', $script:pausedContainer) | Out-Host
                    Measure-QueueLimit
                }
                $process.StandardInput.WriteLine('GO')
                $process.StandardInput.Flush()
            } elseif ($event.event -eq 'sample') {
                Measure-QueueLimit
                $process.StandardInput.WriteLine('GO')
                $process.StandardInput.Flush()
            } elseif ($event.event -eq 'resume') {
                Measure-QueueLimit
                Invoke-Docker @('unpause', $script:pausedContainer) | Out-Host
                $script:pausedContainer = $null
                $process.StandardInput.WriteLine('GO')
                $process.StandardInput.Flush()
            } elseif ($event.event -eq 'done') {
                $done = $event
            } else { throw "Unknown client event: $line" }
            $reading = $process.StandardOutput.ReadLineAsync()
        }
        Assert-True ($process.WaitForExit(10000)) "$Name client did not exit"
        $errorText = $errors.Result
        Assert-True ($process.ExitCode -eq 0) "$Name client failed: $errorText"
        Assert-True ($null -ne $done) "$Name client did not report a result"
        return $done
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
            try { & docker rm -f $clientName *> $null }
            catch { <# --rm kann denselben Container bereits entfernen; Originalfehler erhalten. #> }
        }
        $process.Dispose()
    }
}

# S2 prüft die aufgelöste Konfiguration, ohne ihre Zugangsdaten auszugeben.
function Test-ComposeStart {
    Invoke-Docker @('compose', 'config', '--quiet') | Out-Null
    $raw = Invoke-Docker @('compose', 'config', '--format', 'json')
    $configuration = $raw | ConvertFrom-Json
    foreach ($service in $configuration.services.PSObject.Properties) {
        Assert-True (-not $service.Value.ports) "Published host port on $($service.Name)"
    }
    Assert-True (-not $configuration.services.'chat-service'.container_name) 'Fixed chat-service container name'
    Invoke-Docker @('compose', 'up', '-d', '--build') | Out-Host
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $raw = Invoke-Docker @('compose', 'ps', '-a', '--format', 'json')
        $containers = @($raw -split "`n" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
        $running = @($containers | Where-Object { $_.State -eq 'running' })
        $unhealthy = @($containers | Where-Object { $_.Health -and $_.Health -ne 'healthy' })
        if ($running.Count -eq 4 -and $unhealthy.Count -eq 0) {
            foreach ($container in $containers) {
                $inspect = Invoke-Docker @('inspect', $container.ID)
                $details = @($inspect | ConvertFrom-Json)[0]
                $bindings = @($details.HostConfig.PortBindings.PSObject.Properties)
                Assert-True ($bindings.Count -eq 0) 'Container unexpectedly publishes a host port'
            }
            return [pscustomobject]@{ Services = 4; PublishedHostPorts = 0 }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'S2: not all four services are running and healthy'
}

# S8 verlangt auch im Gesamtlauf einen echten erfolgreichen Java-Stiltest.
function Test-SourceRules {
    # Ein fremdes globales Ignore-File ist für die Prüfung versionierter Dateien unerheblich.
    $tracked = & git -c 'core.excludesFile=' ls-files .env tempContext
    Assert-True ($LASTEXITCODE -eq 0) 'git ls-files failed'
    Assert-True (-not $tracked) '.env or tempContext is tracked'
    $report = Join-Path $projectRoot 'chat-service/target/surefire-reports/TEST-ch.benedict.m321.chatservice.CodeStyleTest.xml'
    Assert-True (Test-Path -LiteralPath $report) 'Run mvn clean test before S8'
    [xml]$test = Get-Content -Raw -LiteralPath $report
    Assert-True ([int]$test.testsuite.tests -gt 0 -and $test.testsuite.failures -eq '0' -and $test.testsuite.errors -eq '0' -and $test.testsuite.skipped -eq '0') 'CodeStyleTest was not successful'
    return [pscustomobject]@{ CodeStyleTest = 'passed'; TrackedSecrets = 0 }
}

# All behält denselben Stack und dessen Daten zwischen S2 und S8 unverändert bei.
try {
    $scenarios = @($Scenario)
    if ($Scenario -eq 'All') { $scenarios = @('S2', 'S3', 'S4', 'S5', 'S6', 'S7', 'S8') }
    Invoke-Docker @('pull', 'node:22-alpine') | Out-Host
    foreach ($name in $scenarios) {
        Write-Host "Starting $name"
        $watch = [Diagnostics.Stopwatch]::StartNew()
        if ($name -eq 'S2') { $measurement = Test-ComposeStart }
        elseif ($name -eq 'S8') { $measurement = Test-SourceRules }
        else {
            if ($name -in @('S3', 'S6')) {
                Invoke-Docker @('compose', 'up', '-d', '--scale', 'chat-service=2') | Out-Host
            }
            $instances = @(Get-ChatInstances)
            if ($name -in @('S3', 'S4', 'S6')) {
                Assert-True ($instances.Count -eq 2) "$name requires two chat-service instances"
            }
            $measurement = Invoke-Client $name $instances
            if ($name -eq 'S4') {
                $measurement | Add-Member -NotePropertyName QueueRemovalSeconds -NotePropertyValue $scaleMeasurement
            } elseif ($name -eq 'S5') {
                Invoke-Docker @('compose', 'start', 'postgres', 'batch-writer') | Out-Host
                $stoppedPersistence = $false
                Assert-StoredMessages $measurement
                $measurement | Add-Member -NotePropertyName PersistedExactIds -NotePropertyValue 100
            } elseif ($name -eq 'S6') {
                $queueSamples | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'queue-samples.json')
                $maximum = ($queueSamples | Measure-Object -Property Total -Maximum).Maximum
                $measurement | Add-Member -NotePropertyName MaxObservedQueueTotal -NotePropertyValue $maximum
                Assert-PersistenceQueues
            }
        }
        $results.Add([pscustomobject]@{
            Scenario = $name; Passed = $true; DurationSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
            Measurement = $measurement
        })
        $results | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'results.json')
        Write-Host "$name PASSED"
    }
} catch {
    $_ | Out-String | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'failure.txt')
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    if ($pausedContainer) { & docker unpause $pausedContainer | Out-Host }
    if ($stoppedPersistence) { & docker compose start postgres batch-writer | Out-Host }
}
