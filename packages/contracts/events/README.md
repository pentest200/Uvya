# Uvya event contracts

Durable event envelopes are versioned under `v1/`. Every event uses the same required
envelope: `eventId` (UUID), `eventType`, `eventVersion`, `occurredAt` (UTC RFC 3339),
`traceId`, `correlationId`, `idempotencyKey`, and a structured `payload`. Consumers must use the event
type and version together and must tolerate additive payload fields.

| Schema | Payload contract |
| --- | --- |
| `message.created.v1` | Message identity, chat/sender/device identity, client id, explicit per-chat sequence, type, body, optional reply/forward references, version, and status. |
| `message.edited.v1` | Message/chat identity, editor identity, new version, type, and body. |
| `message.deleted.v1` | Message/chat identity, deleting actor, and new version. |
| `message.delivered.v1` | Message/chat/user identity and delivery timestamp. |
| `message.read.v1` | Chat/user identity and the monotonic last-read sequence. |
| `message.reaction.added.v1` | Message/chat/user identity and reaction type. |
| `message.reaction.removed.v1` | Message/chat/user identity and reaction type. |
| `chat.created.v1` | Chat identity, creator, chat type, and optional title. |
| `group.member.added.v1` | Chat, member, and role. |
| `group.member.removed.v1` | Chat and removed member identity. |
| `notification.requested.v1` | Notification identity, recipient, template, and provider-neutral data. |
| `search.index.requested.v1` | Search document identity and the source aggregate version to index. |
| `moderation.reported.v1` | Report identity, reported resource, reporter, and moderation reason. |
| `analytics.event.v1` | Privacy-reviewed analytics name, actor, aggregate, and event properties. |

The API gateway currently produces `chat.created`, `group.member.added`,
`group.member.removed`, `message.created`, `message.edited`, `message.read`, and reaction
events through the data services. All listed event names are provisioned as Kafka topics;
the API gateway currently emits only the domain events produced by its implemented services.
