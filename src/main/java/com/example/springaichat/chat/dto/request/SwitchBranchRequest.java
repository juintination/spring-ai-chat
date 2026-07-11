package com.example.springaichat.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SwitchBranchRequest(
	@NotBlank(message = "메시지 id는 필수입니다")
	String messageId
) {
}
