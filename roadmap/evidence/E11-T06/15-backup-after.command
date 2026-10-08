docker run --rm --network none -v "$PWD/deploy/backup:/review:ro" --entrypoint /opt/backup/bin/python agilityhub-backup:2 -m unittest discover -s /review -p test_backup.py
