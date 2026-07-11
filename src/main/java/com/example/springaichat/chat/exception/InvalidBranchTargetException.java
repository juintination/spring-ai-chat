package com.example.springaichat.chat.exception;

import com.example.springaichat.common.exception.BusinessException;

public class InvalidBranchTargetException extends BusinessException {

	private static final String MESSAGE = "유저 메시지에 대해서만 재생성/편집할 수 있습니다";

	public InvalidBranchTargetException() {
		super(MESSAGE);
	}

}
