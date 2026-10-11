gh run view 38121919455 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/59-round3-review-baseline-ci.log 2>&1
