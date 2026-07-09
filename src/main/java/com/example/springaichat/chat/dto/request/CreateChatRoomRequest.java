package com.example.springaichat.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateChatRoomRequest(
	@NotBlank(message = "제목은 필수입니다")
	String title
) {
}
