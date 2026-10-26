docker run --rm --network none --entrypoint /bin/sh agilityhub-core-api:review-fixes-20261024 -c 'test "$(id -u)" = 10001 && test ! -e /usr/bin/pebble && test -r /app/seeds/club-canic-demo.yaml && java -version && sha256sum /app/seeds/club-canic-demo.yaml /app/seeds/club-canic.yaml'
Executed under shared heavy.sh lock.
