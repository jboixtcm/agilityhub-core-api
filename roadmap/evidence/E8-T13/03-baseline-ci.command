gh run list --branch main --limit 1 --json databaseId,headSha,status,conclusion,url --jq '.[] | {databaseId, commit: (.headSha[0:7] + " [truncated]"), status, conclusion, url}'
