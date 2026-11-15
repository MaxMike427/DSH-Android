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
$headRef = & gh api "/repos/$Repo/git/ref/heads/$Branch" 2>$null | Out-String
$branchExists = ($LASTEXITCODE -eq 0 -and $headRef.Trim())
if (-not $branchExists) {
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
    Write-Host "seeded branch with $seedPath"
}

# Reuse blobs that already exist with identical content so a republish only
# uploads what actually changed (and stays well under the proxy body limit).
$existing = @{}
if ($branchExists) {
    $headSha = ($headRef | ConvertFrom-Json).object.sha
    $treeSha = (& gh api "/repos/$Repo/git/commits/$headSha" --jq '.tree.sha' 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0 -and $treeSha) {
        $raw = & gh api "/repos/$Repo/git/trees/$treeSha`?recursive=1" --jq '.tree[] | select(.type=="blob") | "\(.path)`t\(.sha)"' 2>$null
        foreach ($line in $raw) {
            $parts = $line -split "`t", 2
            if ($parts.Count -eq 2) { $existing[$parts[0]] = $parts[1] }
        }
    }
}

$entries = @()
foreach ($relative in $all) {
    $path = $relative -replace '\\', '/'
    $full = Join-Path $root $relative
    if (-not (Test-Path $full -PathType Leaf)) { continue }
    $bytes = [IO.File]::ReadAllBytes($full)

    # Git's blob id is sha1("blob <len>\0" + content); comparing that against the
    # remote tree reuses unchanged blobs without uploading them again.
    $sha1 = [Security.Cryptography.SHA1]::Create()
    $header = [Text.Encoding]::ASCII.GetBytes("blob $($bytes.Length)`0")
    $concat = New-Object byte[] ($header.Length + $bytes.Length)
    [Array]::Copy($header, 0, $concat, 0, $header.Length)
    [Array]::Copy($bytes, 0, $concat, $header.Length, $bytes.Length)
    $gitSha = ([BitConverter]::ToString($sha1.ComputeHash($concat)) -replace '-', '').ToLowerInvariant()

    if ($existing.ContainsKey($path) -and $existing[$path] -eq $gitSha) {
        $entries += @{ path = $path; mode = "100644"; type = "blob"; sha = $gitSha }
        continue
    }

    $blob = Invoke-GhJson -Arguments @(
        "-X", "POST", "/repos/$Repo/git/blobs"
    ) -Body @{
        content  = [Convert]::ToBase64String($bytes)
        encoding = "base64"
    }
    $entries += @{ path = $path; mode = "100644"; type = "blob"; sha = $blob.sha }
    Write-Host "  blob $path ($($bytes.Length) bytes)"
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
    # force is required: the tree was uploaded before this ref lookup, so a
    # concurrent publish can advance the branch in between. The local tree is
    # authoritative for this publish, so reset the ref to it.
    Invoke-GhJson -Arguments @(
        "-X", "PATCH", "/repos/$Repo/git/refs/heads/$Branch"
    ) -Body @{ sha = $commit.sha; force = $true } | Out-Null
} else {
    Invoke-GhJson -Arguments @(
        "-X", "POST", "/repos/$Repo/git/refs"
    ) -Body @{ ref = "refs/heads/$Branch"; sha = $commit.sha } | Out-Null
}

Write-Host "pushed commit $($commit.sha)"
