package com.uvya.apigateway.message.web;

import java.util.List;

public record MessageHistoryResponse(List<MessageResponse> messages, String nextCursor, boolean hasMore) {
}
