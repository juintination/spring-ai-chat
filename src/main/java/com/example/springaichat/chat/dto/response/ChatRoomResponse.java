package com.example.springaichat.chat.dto.response;

import com.example.springaichat.chat.entity.ChatRoom;
import java.time.LocalDateTime;

public record ChatRoomResponse(
	String id,
	String title,
	LocalDateTime createdAt
) {

	public static ChatRoomResponse from(ChatRoom chatRoom) {
		return new ChatRoomResponse(chatRoom.getId(), chatRoom.getTitle(), chatRoom.getCreatedAt());
	}

}
