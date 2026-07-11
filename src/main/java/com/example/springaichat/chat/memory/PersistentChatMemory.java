package com.example.springaichat.chat.memory;

import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.chat.service.MessageBranchService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화 메모리를 messages 테이블(브랜치 트리)에 영속화하는 ChatMemory 구현체.
 * MessageChatMemoryAdvisor가 유저/어시스턴트 메시지 저장과 히스토리 조회를 이 클래스에 위임하고,
 * 실제 트리 조작(부모/activeChild/activeLeaf 갱신)은 MessageBranchService가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class PersistentChatMemory implements ChatMemory {

	private final ChatRoomRepository chatRoomRepository;
	private final MessageRepository messageRepository;
	private final MessageBranchService messageBranchService;

	@Override
	@Transactional
	public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
		List<Message> templates = messages.stream()
				.map(this::toEntity)
				.filter(Objects::nonNull)
				.toList();

		messageBranchService.appendToActiveBranch(conversationId, templates);
	}

	@Override
	@Transactional(readOnly = true)
	public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
		// 창 크기 제한은 MessageBranchService.getActivePath가 walk 단계에서부터 적용한다
		return messageBranchService.getActivePath(conversationId).stream()
				.map(this::toAiMessage)
				.toList();
	}

	@Override
	@Transactional
	public void clear(String conversationId) {
		List<Message> messages = messageRepository.findByChatRoomIdOrderByIdAsc(conversationId);
		messageRepository.deleteAll(messages);

		chatRoomRepository.findById(conversationId)
				.ifPresent(chatRoom -> chatRoom.updateActiveLeafMessageId(null));
	}

	private Message toEntity(org.springframework.ai.chat.messages.Message message) {
		MessageRole role = switch (message.getMessageType()) {
			case USER -> MessageRole.USER;
			case ASSISTANT -> MessageRole.ASSISTANT;
			default -> null;
		};
		if (role == null) {
			return null;
		}
		return Message.builder()
				.role(role)
				.content(message.getText())
				.build();
	}

	private org.springframework.ai.chat.messages.Message toAiMessage(Message message) {
		return switch (message.getRole()) {
			case USER -> new UserMessage(message.getContent());
			case ASSISTANT -> new AssistantMessage(message.getContent());
		};
	}

}
