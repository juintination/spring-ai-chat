package com.example.springaichat.chat.dto.response;

import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.service.MessageBranchService.BranchNode;
import java.time.LocalDateTime;
import java.util.List;

public record MessageResponse(
	String id,
	MessageRole role,
	String content,
	LocalDateTime createdAt,
	int siblingIndex,
	int siblingCount,
	List<String> siblingMessageIds
) {

	public static MessageResponse from(BranchNode node) {
		return new MessageResponse(
				node.message().getId(),
				node.message().getRole(),
				node.message().getContent(),
				node.message().getCreatedAt(),
				node.siblingIndex(),
				node.siblingCount(),
				node.siblingIds());
	}

}
