package com.uvya.apigateway.user.web;

import java.util.List;

public record ContactUploadResponse(List<ContactMatchResponse> matches) {
}
