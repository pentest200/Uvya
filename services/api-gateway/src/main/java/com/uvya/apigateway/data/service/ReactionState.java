package com.uvya.apigateway.data.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReactionState(UUID messageId, Map<String, Long> counts, List<String> myReactions) {
}
