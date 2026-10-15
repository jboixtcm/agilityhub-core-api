gh run list --branch main --limit 4 --json databaseId,headSha,status,conclusion,displayTitle --jq '.[] | {databaseId, commit: .headSha[0:8], status, conclusion, displayTitle}'
