package com.example.springaichat.chat.dto.response;

public record EditAndResendResponse(
	MessageResponse userMessage,
	MessageResponse assistantMessage
) {
}
