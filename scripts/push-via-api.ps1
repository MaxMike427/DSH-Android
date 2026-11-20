# Pushes the current branch's content to GitHub through the REST API.
#
# `github.com:443` is unreachable from this machine, so `git push` cannot land.
# `api.github.com` works, so the commit is rebuilt server-side: one blob per
# changed file, a tree on top of the branch's current tree, a commit with the
# branch head as parent, then the ref is moved.
#
# `-Base` matters here: this working copy's history was created independently of
# the one on GitHub (same commit messages, different SHAs), so replaying only the
# tip commit would lose the work underneath. Pointing the base at the commit
# before the local work started replays every file that work touched, on top of
# whatever GitHub currently has.
#
# Usage: pwsh -File scripts/push-via-api.ps1 -Branch main -Base a9318ca
param(
  [Parameter(Mandatory = $true)][string]$Branch,
  [string]$Base = 'HEAD~1',
  [string]$Message = '',
  [string]$Repo = 'MaxMike427/DSH-Android'
)

$ErrorActionPreference = 'Stop'
$tmp = Join-Path $env:TEMP 'pushapi'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Invoke-Api([string]$method, [string]$path, [string]$bodyFile) {
  if ($bodyFile) {
    return gh api --method $method "repos/$Repo/$path" --input $bodyFile
  }
  return gh api --method $method "repos/$Repo/$path"
}

# Branch head as GitHub knows it.
$head = (gh api "repos/$Repo/git/ref/heads/$Branch" | ConvertFrom-Json).object.sha
$headCommit = gh api "repos/$Repo/git/commits/$head" | ConvertFrom-Json
$baseTree = $headCommit.tree.sha
"remote $Branch head: $head"
"remote tree:        $baseTree"

# Local content to publish.
$localHead = (git rev-parse HEAD).Trim()
# git prints the body as one line per array element; join it back into a string
# or the API rejects the payload as a non-string message.
$localMsg = if ($Message) { $Message } else { ((git log -1 --pretty=%B) -join "`n").TrimEnd() }
$parent = (git rev-parse $Base).Trim()
"local HEAD:         $localHead (base $Base = $parent)"

# Files touched since the base, as repo-relative paths with git's status.
$changed = git diff --name-status $parent $localHead | ForEach-Object {
  $parts = $_ -split "`t"
  [pscustomobject]@{ Status = $parts[0]; Path = $parts[1] }
}
"changed files:      $($changed.Count)"

$entries = @()
foreach ($file in $changed) {
  $entry = @{ path = $file.Path; mode = '100644'; type = 'blob' }
  if ($file.Status -eq 'D') {
    # Deleting is expressed by omitting the path from a tree that has it, so a
    # null sha is used to drop it.
    $entry.sha = $null
    $entries += $entry
    "  deleted: $($file.Path)"
    continue
  }

  $full = Join-Path (Get-Location) $file.Path
  if (-not (Test-Path $full)) { throw "missing file: $($file.Path)" }
  $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($full))

  $blobBody = Join-Path $tmp 'blob.json'
  @{ content = $base64; encoding = 'base64' } | ConvertTo-Json -Compress |
    Set-Content -Path $blobBody -Encoding utf8NoBOM
  $blob = Invoke-Api 'POST' 'git/blobs' $blobBody | ConvertFrom-Json
  $entry.sha = $blob.sha
  $entries += $entry
  "  blob: $($blob.sha.Substring(0,7))  $($file.Path)"
}

$treeBody = Join-Path $tmp 'tree.json'
@{ base_tree = $baseTree; tree = $entries } | ConvertTo-Json -Depth 6 -Compress |
  Set-Content -Path $treeBody -Encoding utf8NoBOM
$tree = Invoke-Api 'POST' 'git/trees' $treeBody | ConvertFrom-Json
"new tree:           $($tree.sha)"

$commitBody = Join-Path $tmp 'commit.json'
@{ message = $localMsg; tree = $tree.sha; parents = @($head) } |
  ConvertTo-Json -Depth 6 -Compress | Set-Content -Path $commitBody -Encoding utf8NoBOM
$commit = Invoke-Api 'POST' 'git/commits' $commitBody | ConvertFrom-Json
"new commit:         $($commit.sha)"

$refBody = Join-Path $tmp 'ref.json'
@{ sha = $commit.sha; force = $false } | ConvertTo-Json -Compress |
  Set-Content -Path $refBody -Encoding utf8NoBOM
Invoke-Api 'PATCH' "git/refs/heads/$Branch" $refBody | Out-Null
"moved refs/heads/$Branch -> $($commit.sha)"
