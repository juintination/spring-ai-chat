package com.example.springaichat.chat.service;

import com.example.springaichat.chat.dto.ChatRoomTitle;
import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.response.ChatResponse;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.memory.PersistentChatMemory;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Log4j2
@Service
public class ChatService {

	private static final String DEFAULT_SYSTEM_PROMPT = "당신은 친절하고 유능한 AI 어시스턴트입니다. 한국어로 답변합니다.";

	private static final String TITLE_GENERATION_PROMPT = """
			다음은 사용자가 채팅방에서 보낸 첫 메시지다. 이 대화에 어울리는 채팅방 제목을 만들어라.

			첫 메시지: {message}
			""";

	private final ChatClient chatClient;
	private final ChatClient titleChatClient;
	private final ChatRoomRepository chatRoomRepository;
	private final MessageRepository messageRepository;

	public ChatService(ChatClient.Builder chatClientBuilder, PersistentChatMemory chatMemory,
			ChatRoomRepository chatRoomRepository, MessageRepository messageRepository) {
		// 제목 생성은 대화 메모리를 오염시키면 안 되므로 어드바이저 없는 클라이언트를 별도로 둔다
		this.titleChatClient = chatClientBuilder.build();
		this.chatClient = this.titleChatClient.mutate()
				.defaultSystem(DEFAULT_SYSTEM_PROMPT)
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
				.build();
		this.chatRoomRepository = chatRoomRepository;
		this.messageRepository = messageRepository;
	}

	public ChatResponse chat(String chatRoomId, ChatRequest request) {
		validateChatRoomExists(chatRoomId);
		boolean firstConversation = isFirstConversation(chatRoomId);

		String content = chatClient.prompt()
				.user(request.message())
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatRoomId))
				.call()
				.content();

		if (firstConversation) {
			generateTitleAsync(chatRoomId, request.message());
		}
		return ChatResponse.of(content);
	}

	public Flux<String> chatStream(String chatRoomId, ChatRequest request) {
		validateChatRoomExists(chatRoomId);
		boolean firstConversation = isFirstConversation(chatRoomId);

		return chatClient.prompt()
				.user(request.message())
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatRoomId))
				.stream()
				.content()
				.doOnComplete(() -> {
					if (firstConversation) {
						generateTitleAsync(chatRoomId, request.message());
					}
				});
	}

	private boolean isFirstConversation(String chatRoomId) {
		return !messageRepository.existsByChatRoomId(chatRoomId);
	}

	private void generateTitleAsync(String chatRoomId, String firstMessage) {
		Mono.fromRunnable(() -> generateAndApplyTitle(chatRoomId, firstMessage))
				.subscribeOn(Schedulers.boundedElastic())
				.subscribe();
	}

	private void generateAndApplyTitle(String chatRoomId, String firstMessage) {
		try {
			ChatRoomTitle generated = titleChatClient.prompt()
					.user(user -> user.text(TITLE_GENERATION_PROMPT).param("message", firstMessage))
					.call()
					.entity(ChatRoomTitle.class);

			chatRoomRepository.findById(chatRoomId).ifPresent(chatRoom -> {
				chatRoom.updateTitle(generated.title());
				chatRoomRepository.save(chatRoom);
			});
		} catch (Exception e) {
			// 제목 생성 실패는 대화 흐름에 영향을 주지 않도록 로그만 남긴다
			log.warn("채팅방 제목 생성에 실패했습니다. chatRoomId={}", chatRoomId, e);
		}
	}

	private void validateChatRoomExists(String chatRoomId) {
		if (!chatRoomRepository.existsById(chatRoomId)) {
			throw new ChatRoomNotFoundException();
		}
	}

}
