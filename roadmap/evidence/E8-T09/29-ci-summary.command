gh run view 37778200662 --json databaseId,status,conclusion,url,jobs --jq '{databaseId,status,conclusion,url,jobs:[.jobs[] | {name,status,conclusion}]}'
