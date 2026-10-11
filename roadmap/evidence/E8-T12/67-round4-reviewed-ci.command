gh run view 38127687490 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/67-round4-reviewed-ci.log 2>&1
