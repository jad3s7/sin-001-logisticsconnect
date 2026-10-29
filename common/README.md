# common/ — shared ActiveMQ broker

The broker that decouples `delay-stage-service` (producer) from
`transit-service` and (stage 4) `alertbot` (consumers).

**One topic only:** `package-status-topic`.

## Quick start

```bash
cd common
docker compose up -d              # or:  make up
docker compose ps                 # STATUS column shows (healthy) when ready
```

- OpenWire (services connect here): `tcp://localhost:61616`
- Web console: `http://localhost:8161` — user `admin`, password `admin`
- First pull is ~200 MB; subsequent starts are instant

```bash
docker compose down               # stop broker, keep volume
docker compose down -v            # stop AND wipe broker state
docker compose logs -f activemq   # tail
```

## The topic — `package-status-topic`

| | |
|---|---|
| Name | `package-status-topic` |
| Broker URL | `tcp://localhost:61616` |
| Credentials | `admin` / `admin` |
| Producer | `delay-stage-service`, on `POST /delay-stage/{hubId}` |
| Consumers | `transit-service` (`PackageStatusSubscriber`); `alertbot` (stage 4) |

### Message payload

```json
{ "hubId": "H-501", "stage": 5, "timestamp": "2026-07-18T10:15:00Z" }
```

- `hubId` — normalized hub id (UPPERCASE, no whitespace)
- `stage` — integer `0..8`
- `timestamp` — ISO-8601 UTC instant of the change

Defined once in `co.wethinkcode.logisticsconnect.mq.PackageStatusMessage`
(duplicated into each service's source tree, per the brief).

### Overriding the broker URL / topic / creds

Each service's `MqConfig` class reads:

| Env var | Default |
|---|---|
| `MQ_BROKER_URL` | `tcp://localhost:61616` |
| `MQ_TOPIC` | `package-status-topic` |
| `MQ_USER` | `admin` |
| `MQ_PASSWORD` | `admin` |

See `.env.example`. Copy to `.env` for Compose, or `export` for the services.

## Proving a message flows end-to-end

### Automated (recommended)

```bash
cd common
./smoke-test.sh                   # or:  make smoke
```

Requires the four domain services running in other terminals. It will:

1. Start the broker
2. Wait for `:61616`
3. Check all four services `/health`
4. `POST :7052/delay-stage/H-501 {"stage":5}`
5. Poll `:7053/mq-status` until `messagesReceived ≥ 1`
6. Print the received message + `/eta/H-501` as evidence; exit-0 on success

### Manual (three overlapping proofs)

**A. Service logs**

```bash
# delay-stage-service stderr
INFO: Published: {"hubId":"H-501","stage":5,"timestamp":"2026-07-18T10:15:00Z"}

# transit-service stderr
INFO: SUBSCRIBED to package-status-topic at tcp://localhost:61616
      — no further synchronous calls to delay-stage-service
INFO: Received from package-status-topic: H-501 stage=5 ts=2026-07-18T10:15:00Z
```

**B. Transit `/mq-status`**

```bash
curl http://localhost:7053/mq-status | python -m json.tool
```

```json
{
  "connected": true,
  "brokerUrl": "tcp://localhost:61616",
  "topic": "package-status-topic",
  "messagesReceived": 1,
  "messagesRejected": 0,
  "lastMessageAt": "2026-07-18T10:15:00Z",
  "hubsKnown": 1
}
```

**C. ActiveMQ web console**

Open http://localhost:8161 → **admin / admin** → **Topics** → **`package-status-topic`**:

| Metric | Expected |
|---|---|
| Enqueued | incremented by each `POST /delay-stage/{id}` |
| Dequeued | matches Enqueued (at least one consumer connected) |
| Consumers | `1` (transit-service); `2` once alertbot is running |

Navigate into the topic to see the live message list with the JSON body.

## Design notes / tradeoffs

### The decoupling in one sentence

Before stage 3, `/eta` called `GET :7052/delay-stage/{id}` per request.
After stage 3, `transit-service` holds a local `StageCache` fed from the topic
and **never calls `delay-stage-service` synchronously**. `registerRoutes` no
longer even accepts a delay-stage URL — the decoupling is structural, not
configuration.

### Restart semantics (the deliberate trade-off)

**`transit-service` does not replay old messages after restart.** Its cache is
in-memory. When it comes back up, `/eta/{id}` returns

```
404 {"error":"no MQ status received yet for hub: H-501","status":404}
```

until the next `POST /delay-stage/H-501`.

Why this is the right call here:

- ActiveMQ topics are fire-and-forget by default; replay requires **retroactive
  consumers** (`consumer.retroactive=true`) or a retained store, which adds
  complexity not justified by this scaffold.
- Returning an honest 404 ("no current status yet") is more defensible than
  serving stale stage values indefinitely.
- The trade-off is documented and testable (`/eta` before any message → 404,
  `/eta` after message → 200 with `stageObservedAt`).

For production, enable retroactive consumers on the topic and have the
subscriber record `stageObservedAt` per message — the payload shape already
carries the timestamp needed.

### Why an `abstract StatusPublisher`

`delay-stage-service` persists the stage **before** publishing. If the broker
is down, the POST returns `{"published": false}` — the state change is still
recorded, and `/delay-stage/{id}` still reflects it. But `transit-service`
does not see the change until the next successful publish. The `published`
field in the response makes this observable to callers; the alternative
(rolling back the state or blocking the POST on publish) trades liveness for
consistency without any compensating benefit at this scale.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `delay-stage-service` fails at startup with "Failed to connect to broker" | `cd common && docker compose up -d` first |
| `docker compose ps` shows `starting` for >60s | Still pulling; check `docker compose logs -f activemq` |
| Port 61616 or 8161 already in use | `docker compose down`; check `lsof -i :61616 -i :8161` |
| Web console 401 | Wrong credentials — default is `admin/admin`; check `ACTIVEMQ_ADMIN_LOGIN` |
| `/mq-status` says `messagesReceived: 0` after a POST | Check the POST returned `published: true`; check `docker compose logs activemq` for delivery errors |
| `/eta/H-501` 404s after a successful POST | Check hub id case — `hubId` is normalized to UPPERCASE on both sides |