gh run view 38702152670 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/127-round5-session3-ci-jobs.log 2>&1
