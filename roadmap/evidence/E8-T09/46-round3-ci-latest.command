gh run view 37800969752 --json databaseId,status,conclusion,url,jobs --jq '{databaseId,status,conclusion,url,jobs:[.jobs[] | {name,status,conclusion}]}'
