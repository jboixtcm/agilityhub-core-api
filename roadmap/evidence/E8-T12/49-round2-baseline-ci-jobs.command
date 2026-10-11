gh run view 38079234288 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/49-round2-baseline-ci-jobs.log 2>&1
