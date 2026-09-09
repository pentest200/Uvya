# WebSocket gateway

This process is the future realtime edge for Uvya. The foundation milestone deliberately implements only process lifecycle, request ID propagation, structured logging, and health/readiness probes. WebSocket authentication, connection registration, Kafka consumption, and delivery routing are deferred until the realtime protocol is specified.

Run locally with:

```bash
go run ./cmd/ws-gateway
```

The default port is `8081`; configure it with `PORT`.
