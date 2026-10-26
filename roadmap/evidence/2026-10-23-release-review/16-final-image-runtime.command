docker run --rm --network none --entrypoint /bin/sh agilityhub-core-api:review-20261023 -c 'test ! -e /usr/bin/pebble && test "$(id -u)" = 10001 && java -version'
