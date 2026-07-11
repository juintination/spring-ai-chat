package com.example.springaichat.chat.repository;

import com.example.springaichat.chat.entity.Message;
import java.util.List;

public interface MessageRepositoryCustom {

	/**
	 * messageId부터 parentMessage를 재귀적으로 따라 올라가며 최대 limit개까지 조상 체인을 조회한다.
	 * 반환 순서는 messageId(리프)가 첫 번째, 가장 오래된 조상이 마지막이다.
	 */
	List<Message> findAncestorChain(String messageId, int limit);

}
