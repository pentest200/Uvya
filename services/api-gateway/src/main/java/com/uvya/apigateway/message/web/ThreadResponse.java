package com.uvya.apigateway.message.web;

import java.util.List;
import java.util.UUID;

public record ThreadResponse(UUID threadRootMessageId, MessageResponse root, List<MessageResponse> messages,
        String nextCursor, boolean hasMore) {
}
