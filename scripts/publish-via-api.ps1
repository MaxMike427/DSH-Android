# Upload the working tree through the GitHub contents API.
#
# The local network resets `git push` over HTTPS, while the REST API works, so
# this script is the fallback used to publish the repository. It creates blobs
# and a single tree/commit instead of one commit per file.
param(
    [string]$Repo = "MaxMike427/DSH-Android",
    [string]$Branch = "main",
    [string]$Message = "Update repository contents"
)

$ErrorActionPreference = "Stop"
$root = (Get-Location).Path

function Invoke-GhJson {
    param([string[]]$Arguments, [object]$Body)
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    $tmp = Join-Path $env:TEMP ("gh-body-" + [guid]::NewGuid().ToString("N") + ".json")
    [IO.File]::WriteAllText($tmp, $json, [Text.UTF8Encoding]::new($false))
    try {
        $out = & gh api @Arguments --input $tmp 2>&1
        if ($LASTEXITCODE -ne 0) { throw ($out -join "`n") }
        return ($out | Out-String | ConvertFrom-Json)
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}

# Collect tracked files plus anything not ignored.
$files = & git ls-files
$untracked = & git ls-files --others --exclude-standard
$all = @($files + $untracked | Sort-Object -Unique | Where-Object { $_ })

Write-Host "uploading $($all.Count) files to $Repo"

# A brand-new repository has no commit, and the Git data API refuses to create
# blobs until one exists. Seed the default branch through the contents API.
$seeded = $false
$headRef = & gh api "/repos/$Repo/git/ref/heads/$Branch" 2>$null | Out-String
if ($LASTEXITCODE -ne 0 -or -not $headRef.Trim()) {
    $seedPath = "README.md"
    if (-not ($all -contains $seedPath)) { $seedPath = ($all | Select-Object -First 1) }
    $seedFull = Join-Path $root $seedPath
    $seedBody = @{
        message = "Initial commit"
        branch  = $Branch
        content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($seedFull))
    }
    Invoke-GhJson -Arguments @(
        "-X", "PUT", "/repos/$Repo/contents/$([uri]::EscapeDataString($seedPath))"
    ) -Body $seedBody | Out-Null
    $all = $all | Where-Object { $_ -ne $seedPath }
    $seeded = $true
    Write-Host "seeded branch with $seedPath"
}

$entries = @()
foreach ($relative in $all) {
    $path = $relative -replace '\\', '/'
    $full = Join-Path $root $relative
    if (-not (Test-Path $full -PathType Leaf)) { continue }
    $bytes = [IO.File]::ReadAllBytes($full)
    $blob = Invoke-GhJson -Arguments @(
        "-X", "POST", "/repos/$Repo/git/blobs"
    ) -Body @{
        content  = [Convert]::ToBase64String($bytes)
        encoding = "base64"
    }
    $entries += @{ path = $path; mode = "100644"; type = "blob"; sha = $blob.sha }
    Write-Host "  blob $path"
}

# Preserve the executable bit for shell scripts.
foreach ($entry in $entries) {
    if ($entry.path -like "*.sh" -or $entry.path -eq "gradlew") {
        $entry.mode = "100755"
    }
}

$tree = Invoke-GhJson -Arguments @(
    "-X", "POST", "/repos/$Repo/git/trees"
) -Body @{ tree = $entries }

$parentSha = $null
try {
    $ref = & gh api "/repos/$Repo/git/ref/heads/$Branch" 2>$null | Out-String
    if ($LASTEXITCODE -eq 0 -and $ref.Trim()) {
        $parentSha = ($ref | ConvertFrom-Json).object.sha
    }
} catch { $parentSha = $null }

$commitBody = @{ message = $Message; tree = $tree.sha }
if ($parentSha) { $commitBody.parents = @($parentSha) }

$commit = Invoke-GhJson -Arguments @(
    "-X", "POST", "/repos/$Repo/git/commits"
) -Body $commitBody

if ($parentSha) {
    Invoke-GhJson -Arguments @(
        "-X", "PATCH", "/repos/$Repo/git/refs/heads/$Branch"
    ) -Body @{ sha = $commit.sha; force = $false } | Out-Null
} else {
    Invoke-GhJson -Arguments @(
        "-X", "POST", "/repos/$Repo/git/refs"
    ) -Body @{ ref = "refs/heads/$Branch"; sha = $commit.sha } | Out-Null
}

Write-Host "pushed commit $($commit.sha)"
