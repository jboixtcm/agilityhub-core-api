gh run view 38454534034 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs: [.jobs[] | {name,status,conclusion}]}' > roadmap/evidence/E8-T12/116-round5-resume-whole-ci.log 2>&1
