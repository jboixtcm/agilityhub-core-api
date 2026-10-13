gh run view 38454534034 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/120-round5-resume-final-ci.log 2>&1
