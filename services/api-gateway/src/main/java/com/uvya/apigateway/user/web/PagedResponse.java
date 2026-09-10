package com.uvya.apigateway.user.web;

import java.util.List;

import org.springframework.data.domain.Page;

public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PagedResponse<T> from(Page<T> source) {
        return new PagedResponse<>(source.getContent(), source.getNumber(), source.getSize(), source.getTotalElements(),
                source.getTotalPages());
    }
}
