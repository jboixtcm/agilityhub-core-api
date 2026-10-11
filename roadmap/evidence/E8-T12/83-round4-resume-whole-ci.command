gh run view 38135935642 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/83-round4-resume-whole-ci.log 2>&1
