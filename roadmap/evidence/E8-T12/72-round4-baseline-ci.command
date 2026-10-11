gh run view 38132636473 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/72-round4-baseline-ci.log 2>&1
