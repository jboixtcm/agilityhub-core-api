docker run --rm --network none --read-only --entrypoint sh ghcr.io/jboixtcm/agilityhub-core-api:release-audit-no-pebble -c 'test ! -e /usr/bin/pebble && java -version'
