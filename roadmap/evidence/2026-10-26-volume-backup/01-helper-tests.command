docker run --rm --entrypoint /opt/backup/bin/python -v "$PWD/deploy/backup:/proof:ro" -w /proof agilityhub-backup:2 -m unittest discover -v -p 'test_*.py'
