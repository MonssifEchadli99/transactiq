[CmdletBinding()]
param(
    [ValidateRange(30, 900)]
    [int]$StartupTimeoutSeconds = 300,

    [ValidateRange(15, 300)]
    [int]$ProjectionTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory '..\..')).Path
$mcpSessionId = $null
$mcpProtocolVersion = $null
$investigationBase = $null

function Write-DemoStep {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ("[demo] {0}" -f $Message)
}

function Assert-LastCommandSucceeded {
    param([Parameter(Mandatory)][string]$Description)
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Get-DemoPort {
    param(
        [Parameter(Mandatory)][string]$EnvironmentVariable,
        [Parameter(Mandatory)][int]$Default
    )
    $configured = [Environment]::GetEnvironmentVariable($EnvironmentVariable)
    if ([string]::IsNullOrWhiteSpace($configured)) {
        return $Default
    }
    $parsed = 0
    if (-not [int]::TryParse($configured, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "$EnvironmentVariable must contain a valid TCP port."
    }
    return $parsed
}

function Wait-ForCondition {
    param(
        [Parameter(Mandatory)][string]$Component,
        [Parameter(Mandatory)][scriptblock]$Probe,
        [Parameter(Mandatory)][scriptblock]$Accept,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $value = & $Probe
            if (& $Accept $value) {
                return $value
            }
        } catch {
            # Polling deliberately discards raw transport bodies and request content.
        }
        Start-Sleep -Milliseconds 1000
    }
    throw "$Component was not available within $TimeoutSeconds seconds."
}

function Wait-ForHealth {
    param(
        [Parameter(Mandatory)][string]$Component,
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    Wait-ForCondition -Component $Component -TimeoutSeconds $TimeoutSeconds -Probe {
        Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 3
    } -Accept {
        param($health)
        $null -ne $health -and $health.status -eq 'UP'
    } | Out-Null
}

function Wait-ForDemoQueueCases {
    param(
        [Parameter(Mandatory)][string]$BaseUri,
        [Parameter(Mandatory)][string]$ReviewMerchant,
        [Parameter(Mandatory)][string]$BlockMerchant,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $reviewCase = $null
        $blockCase = $null
        $cursor = $null
        $seenCursors = New-Object 'System.Collections.Generic.HashSet[string]'
        try {
            do {
                if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                    break
                }
                $uri = "$BaseUri/api/v1/fraud-cases?pageSize=100"
                if (-not [string]::IsNullOrWhiteSpace($cursor)) {
                    $uri += '&cursor=' + [uri]::EscapeDataString($cursor)
                }
                $page = Invoke-JsonRequest -Method Get -Uri $uri
                foreach ($item in @($page.items)) {
                    if ($item.merchantId -eq $ReviewMerchant) {
                        $reviewCase = $item
                    } elseif ($item.merchantId -eq $BlockMerchant) {
                        $blockCase = $item
                    }
                }
                if ($null -ne $reviewCase -and $null -ne $blockCase) {
                    return [pscustomobject]@{
                        Review = $reviewCase
                        Block = $blockCase
                    }
                }
                $cursor = [string]$page.nextCursor
                if (-not [string]::IsNullOrWhiteSpace($cursor) -and
                    -not $seenCursors.Add($cursor)) {
                    throw 'Fraud-case queue returned a repeated cursor.'
                }
            } while (-not [string]::IsNullOrWhiteSpace($cursor))
        } catch {
            # A projection may be arriving while pages are read; retry from the first page.
        }
        if ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            Start-Sleep -Milliseconds 1000
        }
    }
    throw "fraud-case queue projection was not available within $TimeoutSeconds seconds."
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][ValidateSet('Get', 'Post')][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [object]$Body,
        [hashtable]$Headers = @{}
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 10
    }
    if ($Method -eq 'Post') {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    Invoke-RestMethod @parameters
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Description
    )
    if ($Actual -ne $Expected) {
        throw "$Description expected '$Expected' but received '$Actual'."
    }
}

function ConvertFrom-McpHttpBody {
    param([Parameter(Mandatory)][string]$Content)
    $trimmed = $Content.Trim()
    if ($trimmed.StartsWith('{')) {
        return $trimmed | ConvertFrom-Json
    }
    foreach ($line in ($Content -split "\r?\n")) {
        if ($line.StartsWith('data:')) {
            $data = $line.Substring(5).Trim()
            if (-not [string]::IsNullOrWhiteSpace($data)) {
                return $data | ConvertFrom-Json
            }
        }
    }
    throw 'MCP response did not contain a JSON-RPC message.'
}

function Get-HttpHeader {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][string]$Name
    )
    $value = $Response.Headers[$Name]
    if ($null -eq $value) {
        return $null
    }
    if ($value -is [string]) {
        return $value
    }
    return [string]($value | Select-Object -First 1)
}

function Invoke-McpHttp {
    param(
        [Parameter(Mandatory)][string]$Endpoint,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Message,
        [switch]$UseSession
    )
    $headers = @{ Accept = 'application/json, text/event-stream' }
    if ($UseSession) {
        $headers['Mcp-Session-Id'] = $script:mcpSessionId
        $headers['MCP-Protocol-Version'] = $script:mcpProtocolVersion
    }
    Invoke-WebRequest `
        -Method Post `
        -Uri $Endpoint `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body ($Message | ConvertTo-Json -Depth 20 -Compress) `
        -TimeoutSec 10 `
        -UseBasicParsing
}

function Invoke-McpRequest {
    param(
        [Parameter(Mandatory)][string]$Endpoint,
        [Parameter(Mandatory)][int]$Id,
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][hashtable]$Parameters
    )
    $response = Invoke-McpHttp -Endpoint $Endpoint -UseSession -Message ([ordered]@{
        jsonrpc = '2.0'
        id = $Id
        method = $Method
        params = $Parameters
    })
    $message = ConvertFrom-McpHttpBody -Content $response.Content
    $errorProperty = $message.PSObject.Properties['error']
    if ($message.id -ne $Id -or ($null -ne $errorProperty -and $null -ne $errorProperty.Value)) {
        throw "MCP method $Method returned an invalid JSON-RPC response."
    }
    return $message.result
}

function New-AuthorizationBody {
    param(
        [Parameter(Mandatory)][guid]$RequestId,
        [Parameter(Mandatory)][string]$CardToken,
        [Parameter(Mandatory)][string]$MerchantId,
        [Parameter(Mandatory)][string]$MerchantCategoryCode
    )
    [ordered]@{
        requestId = $RequestId.ToString()
        cardToken = $CardToken
        merchantId = $MerchantId
        merchantCategoryCode = $MerchantCategoryCode
        amount = 50.00
        currency = 'EUR'
        country = 'DE'
        channel = 'ECOMMERCE'
        transactionTime = [DateTimeOffset]::UtcNow.ToString('o')
    }
}

Push-Location $repositoryRoot
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker is required to run the local demo.'
    }
    & docker compose version | Out-Null
    Assert-LastCommandSucceeded 'Docker Compose availability check'

    $authorizationPort = Get-DemoPort 'TRANSACTIQ_AUTHORIZATION_PORT' 8082
    $fraudManagementPort = Get-DemoPort 'TRANSACTIQ_FRAUD_MANAGEMENT_PORT' 8081
    $caseManagementPort = Get-DemoPort 'TRANSACTIQ_CASE_MANAGEMENT_PORT' 8083
    $caseSearchPort = Get-DemoPort 'TRANSACTIQ_CASE_SEARCH_PORT' 8084
    $investigationPort = Get-DemoPort 'TRANSACTIQ_INVESTIGATION_PORT' 8085
    $authorizationBase = "http://127.0.0.1:$authorizationPort"
    $fraudBase = "http://127.0.0.1:$fraudManagementPort"
    $caseManagementBase = "http://127.0.0.1:$caseManagementPort"
    $caseSearchBase = "http://127.0.0.1:$caseSearchPort"
    $investigationBase = "http://127.0.0.1:$investigationPort"

    Write-DemoStep 'Starting persistent local dependencies without deleting or recreating volumes.'
    & docker compose up -d --wait --wait-timeout $StartupTimeoutSeconds `
        postgres case-postgres redis kafka opensearch
    Assert-LastCommandSucceeded 'Dependency startup'

    Write-DemoStep 'Building and starting the explicit demo profile.'
    & docker compose --profile demo up -d --build `
        fraud-engine authorization-service case-management-service `
        case-search-service investigation-assistant-service
    Assert-LastCommandSucceeded 'Demo service startup'

    Write-DemoStep 'Polling bounded readiness endpoints.'
    Wait-ForHealth 'fraud-engine' "$fraudBase/actuator/health/readiness" $StartupTimeoutSeconds
    Wait-ForHealth 'authorization-service' "$authorizationBase/actuator/health/readiness" $StartupTimeoutSeconds
    Wait-ForHealth 'case-management-service' "$caseManagementBase/actuator/health/readiness" $StartupTimeoutSeconds
    Wait-ForHealth 'case-search-service' "$caseSearchBase/actuator/health/readiness" $StartupTimeoutSeconds
    Wait-ForHealth 'investigation-assistant-service' "$investigationBase/actuator/health/readiness" $StartupTimeoutSeconds

    $runId = [guid]::NewGuid()
    $runSuffix = $runId.ToString('N').Substring(0, 16)
    $clearRequestId = [guid]::NewGuid()
    $reviewRequestId = [guid]::NewGuid()
    $blockRequestId = [guid]::NewGuid()
    $clearAccountId = [guid]::NewGuid()
    $reviewAccountId = [guid]::NewGuid()
    $blockAccountId = [guid]::NewGuid()
    $clearToken = "tok_DemoClear$runSuffix"
    $reviewToken = "tok_DemoReview$runSuffix"
    $blockToken = "tok_DemoBlock$runSuffix"
    $clearMerchant = "demo-clear-$runSuffix"
    $reviewMerchant = "demo-review-$runSuffix"
    $blockMerchant = "demo-block-$runSuffix"
    $analystId = "demo-analyst-$runSuffix"

    Write-DemoStep 'Adding isolated synthetic card fixtures for this run.'
    $fixtureSql = @"
SET search_path TO 'authorization';
INSERT INTO card_accounts
    (account_id, card_token, currency, posted_balance, reserved_amount, created_at, updated_at)
VALUES
    ('$clearAccountId', '$clearToken', 'EUR', 10000.00, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('$reviewAccountId', '$reviewToken', 'EUR', 10000.00, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('$blockAccountId', '$blockToken', 'EUR', 10000.00, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
"@
    & docker compose exec -T postgres psql `
        -v ON_ERROR_STOP=1 `
        -U transactiq_local `
        -d transactiq_authorization `
        -c $fixtureSql | Out-Null
    Assert-LastCommandSucceeded 'Synthetic fixture insertion'

    Write-DemoStep 'Submitting CLEAR, REVIEW, and HIGH_RISK authorization scenarios.'
    $clear = Invoke-JsonRequest -Method Post -Uri "$authorizationBase/api/v1/authorizations" -Body (
        New-AuthorizationBody $clearRequestId $clearToken $clearMerchant '5411')
    $review = Invoke-JsonRequest -Method Post -Uri "$authorizationBase/api/v1/authorizations" -Body (
        New-AuthorizationBody $reviewRequestId $reviewToken $reviewMerchant '7995')
    $block = Invoke-JsonRequest -Method Post -Uri "$authorizationBase/api/v1/authorizations" -Body (
        New-AuthorizationBody $blockRequestId $blockToken $blockMerchant '6051')
    Assert-Equal $clear.decision 'APPROVED' 'CLEAR authorization decision'
    Assert-Equal $review.decision 'APPROVED' 'REVIEW authorization decision'
    Assert-Equal $block.decision 'DECLINED' 'HIGH_RISK authorization decision'
    Assert-Equal $block.declineReason 'HIGH_FRAUD_RISK' 'HIGH_RISK decline reason'

    Write-DemoStep 'Waiting for reliable event consumption and fraud-case creation.'
    $demoCases = Wait-ForDemoQueueCases `
        -BaseUri $caseManagementBase `
        -ReviewMerchant $reviewMerchant `
        -BlockMerchant $blockMerchant `
        -TimeoutSeconds $ProjectionTimeoutSeconds
    $reviewCase = $demoCases.Review
    $blockCase = $demoCases.Block
    Assert-Equal $reviewCase.fraudAssessment 'REVIEW' 'REVIEW case assessment'
    Assert-Equal $blockCase.fraudAssessment 'HIGH_RISK' 'HIGH_RISK case assessment'

    $caseId = [string]$blockCase.caseId
    $detail = Invoke-JsonRequest -Method Get -Uri "$caseManagementBase/api/v1/fraud-cases/$caseId"
    Assert-Equal $detail.status 'NEW' 'New case status'

    Write-DemoStep 'Claiming and resolving the HIGH_RISK case through existing REST contracts.'
    $analystHeaders = @{ 'X-Analyst-Id' = $analystId }
    $claimed = Invoke-JsonRequest -Method Post `
        -Uri "$caseManagementBase/api/v1/fraud-cases/$caseId/claim" `
        -Headers $analystHeaders `
        -Body @{ expectedVersion = [long]$detail.version }
    Assert-Equal $claimed.status 'IN_REVIEW' 'Claimed case status'
    $resolved = Invoke-JsonRequest -Method Post `
        -Uri "$caseManagementBase/api/v1/fraud-cases/$caseId/resolve" `
        -Headers $analystHeaders `
        -Body @{
            expectedVersion = [long]$claimed.version
            outcome = 'CONFIRMED_FRAUD'
            rationale = "Synthetic Cycle 8 evidence confirmed the configured high-risk MCC scenario for run $runSuffix."
        }
    Assert-Equal $resolved.status 'RESOLVED' 'Resolved case status'
    $history = Invoke-JsonRequest -Method Get `
        -Uri "$caseManagementBase/api/v1/fraud-cases/$caseId/history"
    $historyItems = @($history.items)
    if ($historyItems.Count -ne 2) {
        throw "Case history expected exactly two lifecycle events but received $($historyItems.Count)."
    }
    Assert-Equal $historyItems[0].eventType 'CLAIMED' 'First lifecycle event'
    Assert-Equal ([long]$historyItems[0].caseVersion) 1 'Claim lifecycle version'
    Assert-Equal $historyItems[1].eventType 'RESOLVED' 'Second lifecycle event'
    Assert-Equal ([long]$historyItems[1].caseVersion) 2 'Resolution lifecycle version'

    Write-DemoStep 'Waiting for the OpenSearch case projection.'
    $searchUri = "$caseSearchBase/api/v1/fraud-cases/search?q=$blockMerchant&status=RESOLVED&pageSize=20"
    $search = Wait-ForCondition -Component 'case-search projection' `
        -TimeoutSeconds $ProjectionTimeoutSeconds `
        -Probe { Invoke-JsonRequest -Method Get -Uri $searchUri } `
        -Accept {
            param($result)
            @($result.items | Where-Object { $_.caseId -eq $caseId }).Count -eq 1
        }

    $question = 'What findings are supported by the published synthetic evidence?'
    $questionBody = @{ question = $question; maxRelatedCases = 5 }
    Write-DemoStep 'Waiting for offline investigation retrieval and requesting a grounded answer.'
    $retrieval = Wait-ForCondition -Component 'investigation evidence projection' `
        -TimeoutSeconds $ProjectionTimeoutSeconds `
        -Probe {
            Invoke-JsonRequest -Method Post `
                -Uri "$investigationBase/api/v1/fraud-cases/$caseId/investigation/retrieval" `
                -Body $questionBody
        } `
        -Accept {
            param($result)
            $sourceTypes = @($result.focalSources | ForEach-Object { $_.sourceType })
            $sourceTypes -contains 'CASE_EVIDENCE' -and $sourceTypes -contains 'RESOLUTION'
        }
    $answer = Wait-ForCondition -Component 'offline grounded answer' `
        -TimeoutSeconds $ProjectionTimeoutSeconds `
        -Probe {
            Invoke-JsonRequest -Method Post `
                -Uri "$investigationBase/api/v1/fraud-cases/$caseId/investigation/answer" `
                -Body @{ question = $question }
        } `
        -Accept { param($result) $result.groundingStatus -eq 'GROUNDED' }
    if (@($answer.findings).Count -eq 0 -or @($answer.findings[0].citations).Count -eq 0) {
        throw 'Offline grounded answer did not contain a cited finding.'
    }
    $allowedSourceIds = @($retrieval.focalSources | ForEach-Object { $_.sourceId })
    foreach ($relatedCase in @($retrieval.relatedCases)) {
        $allowedSourceIds += @($relatedCase.sources | ForEach-Object { $_.sourceId })
    }
    foreach ($finding in @($answer.findings)) {
        foreach ($citation in @($finding.citations)) {
            if ($allowedSourceIds -notcontains $citation.sourceId) {
                throw 'Offline answer returned a citation outside the retrieval allow-list.'
            }
        }
    }

    Write-DemoStep 'Discovering and invoking both real MCP tools over Streamable HTTP.'
    $mcpEndpoint = "$investigationBase/mcp"
    $initializeResponse = Invoke-McpHttp -Endpoint $mcpEndpoint -Message ([ordered]@{
        jsonrpc = '2.0'
        id = 1
        method = 'initialize'
        params = @{
            protocolVersion = '2025-06-18'
            capabilities = @{}
            clientInfo = @{ name = 'transactiq-cycle8-demo'; version = '1.0' }
        }
    })
    $initialize = ConvertFrom-McpHttpBody -Content $initializeResponse.Content
    $initializeError = $initialize.PSObject.Properties['error']
    if ($initialize.id -ne 1 -or ($null -ne $initializeError -and $null -ne $initializeError.Value)) {
        throw 'MCP initialization failed.'
    }
    $script:mcpSessionId = Get-HttpHeader $initializeResponse 'Mcp-Session-Id'
    $script:mcpProtocolVersion = [string]$initialize.result.protocolVersion
    if ([string]::IsNullOrWhiteSpace($script:mcpSessionId) -or
        [string]::IsNullOrWhiteSpace($script:mcpProtocolVersion)) {
        throw 'MCP initialization did not establish a valid session.'
    }
    Invoke-McpHttp -Endpoint $mcpEndpoint -UseSession -Message ([ordered]@{
        jsonrpc = '2.0'
        method = 'notifications/initialized'
        params = @{}
    }) | Out-Null

    $listed = Invoke-McpRequest -Endpoint $mcpEndpoint -Id 2 -Method 'tools/list' -Parameters @{}
    $toolNames = @($listed.tools | ForEach-Object { $_.name } | Sort-Object)
    $expectedTools = @('answer_fraud_investigation_question', 'retrieve_fraud_case_evidence')
    if ($toolNames.Count -ne 2 -or (Compare-Object $toolNames $expectedTools)) {
        throw 'MCP discovery did not return exactly the two expected read-only tools.'
    }
    $mcpRetrieval = Invoke-McpRequest -Endpoint $mcpEndpoint -Id 3 -Method 'tools/call' -Parameters @{
        name = 'retrieve_fraud_case_evidence'
        arguments = @{ caseId = $caseId; question = $question }
    }
    if ($mcpRetrieval.isError -or $mcpRetrieval.structuredContent.caseId -ne $caseId) {
        throw 'MCP evidence retrieval failed.'
    }
    $mcpAnswer = Invoke-McpRequest -Endpoint $mcpEndpoint -Id 4 -Method 'tools/call' -Parameters @{
        name = 'answer_fraud_investigation_question'
        arguments = @{ caseId = $caseId; question = $question }
    }
    if ($mcpAnswer.isError -or $mcpAnswer.structuredContent.groundingStatus -ne 'GROUNDED') {
        throw 'MCP grounded answer failed.'
    }

    Write-Host ''
    Write-Host 'TransactIQ Cycle 8 demo completed successfully.'
    Write-Host ("  Run ID:                {0}" -f $runId)
    Write-Host ("  CLEAR request:          {0} -> APPROVED" -f $clearRequestId)
    Write-Host ("  REVIEW request:         {0} -> APPROVED, case {1}" -f $reviewRequestId, $reviewCase.caseId)
    Write-Host ("  HIGH_RISK request:      {0} -> DECLINED, case {1}" -f $blockRequestId, $caseId)
    Write-Host ("  Case lifecycle:         NEW -> IN_REVIEW -> RESOLVED ({0})" -f $resolved.resolutionOutcome)
    Write-Host '  Audit history:          CLAIMED v1 -> RESOLVED v2'
    Write-Host ("  Search projection:      {0} matching resolved case" -f @($search.items).Count)
    Write-Host ("  Investigation:          {0} focal source(s), {1}" -f @($retrieval.focalSources).Count, $answer.groundingStatus)
    Write-Host ("  MCP tools invoked:      {0}" -f ($toolNames -join ', '))
    Write-Host '  Runtime:                demo-offline AI profile; no OpenAI model configured'
    Write-Host '  Services remain running; no Docker volume was reset or deleted.'
} catch {
    [Console]::Error.WriteLine("TransactIQ demo failed: {0}" -f $_.Exception.Message)
    try {
        & docker compose --profile demo ps
    } catch {
        # Preserve the original, sanitized demo failure.
    }
    exit 1
} finally {
    if ($null -ne $mcpSessionId -and $null -ne $mcpProtocolVersion -and
        $null -ne $investigationBase) {
        try {
            Invoke-WebRequest `
                -Method Delete `
                -Uri "$investigationBase/mcp" `
                -Headers @{
                    'Mcp-Session-Id' = $mcpSessionId
                    'MCP-Protocol-Version' = $mcpProtocolVersion
                } `
                -TimeoutSec 5 `
                -UseBasicParsing | Out-Null
        } catch {
            # The server owns and eventually expires remaining MCP sessions.
        }
    }
    Pop-Location
}
