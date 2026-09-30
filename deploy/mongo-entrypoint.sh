#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${MONGO_REPLICA_KEY:?Set MONGO_REPLICA_KEY}"
printf '%s\n' "$MONGO_REPLICA_KEY" > /run/mongo-key/keyfile
chown mongodb:mongodb /run/mongo-key /run/mongo-key/keyfile
chmod 400 /run/mongo-key/keyfile
exec /usr/local/bin/docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --auth --keyFile /run/mongo-key/keyfile
