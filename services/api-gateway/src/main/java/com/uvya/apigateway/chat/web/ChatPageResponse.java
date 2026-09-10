package com.uvya.apigateway.chat.web;

import java.util.List;

import org.springframework.data.domain.Page;

public record ChatPageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> ChatPageResponse<T> from(Page<T> source) {
        return new ChatPageResponse<>(source.getContent(), source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
}
