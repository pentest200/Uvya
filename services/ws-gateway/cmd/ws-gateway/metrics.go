package main

import (
	"fmt"
	"net/http"
	"sync/atomic"
)

type gatewayMetrics struct {
	gatewayID       string
	connections     atomic.Int64
	reconnects      atomic.Uint64
	backpressure    atomic.Uint64
	heartbeatMisses atomic.Uint64
	messagesSent    atomic.Uint64
	messagesFailed  atomic.Uint64
	resumes         atomic.Uint64
	routeDrops      atomic.Uint64
	presenceUpdates atomic.Uint64
	presenceDegraded atomic.Uint64
	typingUpdates   atomic.Uint64
	typingDegraded  atomic.Uint64
	redisDegraded   atomic.Uint64
}

func newGatewayMetrics(gatewayID string) *gatewayMetrics {
	return &gatewayMetrics{gatewayID: gatewayID}
}

func (metrics *gatewayMetrics) handler(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "text/plain; version=0.0.4")
	writer.WriteHeader(http.StatusOK)
	fmt.Fprintf(writer, "# TYPE ws_gateway_connections gauge\nws_gateway_connections{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.connections.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_reconnects_total counter\nws_gateway_reconnects_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.reconnects.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_backpressure_total counter\nws_gateway_backpressure_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.backpressure.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_heartbeat_misses_total counter\nws_gateway_heartbeat_misses_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.heartbeatMisses.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_messages_sent_total counter\nws_gateway_messages_sent_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.messagesSent.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_message_failures_total counter\nws_gateway_message_failures_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.messagesFailed.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_resumes_total counter\nws_gateway_resumes_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.resumes.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_route_drops_total counter\nws_gateway_route_drops_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.routeDrops.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_presence_updates_total counter\nws_gateway_presence_updates_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.presenceUpdates.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_presence_degraded_total counter\nws_gateway_presence_degraded_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.presenceDegraded.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_typing_updates_total counter\nws_gateway_typing_updates_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.typingUpdates.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_typing_degraded_total counter\nws_gateway_typing_degraded_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.typingDegraded.Load())
	fmt.Fprintf(writer, "# TYPE ws_gateway_redis_degraded_total counter\nws_gateway_redis_degraded_total{gateway_id=\"%s\"} %d\n",
		metrics.gatewayID, metrics.redisDegraded.Load())
}
