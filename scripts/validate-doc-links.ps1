[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$documentationRoot = Join-Path $repositoryRoot "docs"
$markdownFiles = @(
    Get-Item -LiteralPath (Join-Path $repositoryRoot "README.md")
    Get-ChildItem -LiteralPath $documentationRoot -Recurse -File -Filter "*.md"
)
$linkPattern = [regex]'!?(?:\[[^\]]*\])\((?<target>[^)]+)\)'
$failures = [System.Collections.Generic.List[string]]::new()
$checkedLinks = 0

foreach ($markdownFile in $markdownFiles) {
    $content = Get-Content -Raw -LiteralPath $markdownFile.FullName
    foreach ($match in $linkPattern.Matches($content)) {
        $target = $match.Groups["target"].Value.Trim()
        if ($target.StartsWith("<") -and $target.EndsWith(">")) {
            $target = $target.Substring(1, $target.Length - 2)
        }
        if ($target -match '^(?:https?://|mailto:|app://)' -or $target.StartsWith("#")) {
            continue
        }

        $pathPart = ($target -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) {
            continue
        }
        try {
            $pathPart = [Uri]::UnescapeDataString($pathPart)
        } catch {
            $failures.Add("$($markdownFile.FullName): invalid encoded link '$target'")
            continue
        }

        $checkedLinks++
        $resolvedPath = Join-Path $markdownFile.DirectoryName $pathPart
        if (-not (Test-Path -LiteralPath $resolvedPath)) {
            $relativeSource = $markdownFile.FullName.Substring($repositoryRoot.Length + 1)
            $failures.Add("${relativeSource}: missing local link target '$target'")
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Markdown link validation passed: $checkedLinks local links across $($markdownFiles.Count) files."
