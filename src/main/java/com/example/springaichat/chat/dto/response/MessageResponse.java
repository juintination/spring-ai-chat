package com.example.springaichat.chat.dto.response;

import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import java.time.LocalDateTime;

public record MessageResponse(
	String id,
	MessageRole role,
	String content,
	LocalDateTime createdAt
) {

	public static MessageResponse from(Message message) {
		return new MessageResponse(message.getId(), message.getRole(), message.getContent(), message.getCreatedAt());
	}

}
