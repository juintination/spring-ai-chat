package com.example.springaichat.chat.exception;

import com.example.springaichat.common.exception.BusinessException;

public class MessageNotFoundException extends BusinessException {

	private static final String MESSAGE = "메시지를 찾을 수 없습니다";

	public MessageNotFoundException() {
		super(MESSAGE);
	}

}
