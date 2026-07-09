package com.example.springaichat.chat.dto.response;

public record ChatResponse(
	String content
) {

	public static ChatResponse of(String content) {
		return new ChatResponse(content);
	}

}
