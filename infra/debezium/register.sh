#!/usr/bin/env sh
# Registers (or updates) the order-service outbox connector.
# Run after order-service has started once, so Flyway has created outbox_events.
set -e
DIR=$(dirname "$0")
CONNECT=http://localhost:8083
printf 'Waiting for Kafka Connect'
until curl -sf "$CONNECT/connectors" >/dev/null; do printf '.'; sleep 2; done
echo
python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["config"]))' "$DIR/order-outbox-connector.json" |
  curl -sf -X PUT -H 'Content-Type: application/json' --data @- \
    "$CONNECT/connectors/order-outbox-connector/config"
echo
sleep 3
curl -s "$CONNECT/connectors/order-outbox-connector/status"
echo
