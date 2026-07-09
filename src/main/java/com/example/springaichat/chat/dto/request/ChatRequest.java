package com.example.springaichat.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
	@NotBlank(message = "메시지는 필수입니다")
	String message
) {
}
