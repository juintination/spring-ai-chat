package com.example.springaichat.chat.exception;

import com.example.springaichat.common.exception.BusinessException;

public class ChatRoomNotFoundException extends BusinessException {

	private static final String MESSAGE = "채팅방을 찾을 수 없습니다";

	public ChatRoomNotFoundException() {
		super(MESSAGE);
	}

}
