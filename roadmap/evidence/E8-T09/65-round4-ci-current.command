gh run view 37825075191 --json databaseId,status,conclusion,url,jobs --jq '{databaseId,status,conclusion,url,jobs:[.jobs[] | {name,status,conclusion}]}'
