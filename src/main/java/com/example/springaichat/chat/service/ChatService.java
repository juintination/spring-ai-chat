package com.example.springaichat.chat.service;

import com.example.springaichat.chat.dto.ChatRoomTitle;
import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.response.ChatResponse;
import com.example.springaichat.chat.dto.response.EditAndResendResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.exception.InvalidBranchTargetException;
import com.example.springaichat.chat.exception.MessageNotFoundException;
import com.example.springaichat.chat.memory.PersistentChatMemory;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.chat.service.MessageBranchService.BranchNode;
import com.example.springaichat.chat.tool.DateTimeTools;
import java.util.List;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
	private final ChatClient plainChatClient;
	private final ChatRoomRepository chatRoomRepository;
	private final MessageRepository messageRepository;
	private final MessageBranchService messageBranchService;

	public ChatService(ChatClient.Builder chatClientBuilder, PersistentChatMemory chatMemory,
			ChatRoomRepository chatRoomRepository, MessageRepository messageRepository,
			MessageBranchService messageBranchService, DateTimeTools dateTimeTools) {
		// 제목 생성은 대화 메모리를 오염시키면 안 되므로 어드바이저 없는 클라이언트를 별도로 둔다 (Tool도 등록하지 않는다)
		this.titleChatClient = chatClientBuilder.build();
		// 재시도/재생성/편집은 어드바이저를 우회해 수동으로 히스토리를 구성하므로 시스템 프롬프트만 있는 클라이언트가 필요하다
		this.plainChatClient = this.titleChatClient.mutate()
				.defaultSystem(DEFAULT_SYSTEM_PROMPT)
				.defaultTools(dateTimeTools)
				.build();
		this.chatClient = this.plainChatClient.mutate()
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
				.build();
		this.chatRoomRepository = chatRoomRepository;
		this.messageRepository = messageRepository;
		this.messageBranchService = messageBranchService;
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

	public MessageResponse regenerate(String chatRoomId, String userMessageId) {
		validateChatRoomExists(chatRoomId);
		List<Message> ancestorChain = messageBranchService.getAncestorChain(chatRoomId, userMessageId);
		validateUserMessage(ancestorChain);

		String content = plainChatClient.prompt()
				.messages(toAiMessages(ancestorChain))
				.call()
				.content();

		Message assistantMessage = messageBranchService.appendMessage(chatRoomId, userMessageId,
				MessageRole.ASSISTANT, content);
		return toMessageResponse(chatRoomId, assistantMessage.getId());
	}

	public Flux<String> regenerateStream(String chatRoomId, String userMessageId) {
		validateChatRoomExists(chatRoomId);
		List<Message> ancestorChain = messageBranchService.getAncestorChain(chatRoomId, userMessageId);
		validateUserMessage(ancestorChain);

		StringBuilder buffer = new StringBuilder();
		return plainChatClient.prompt()
				.messages(toAiMessages(ancestorChain))
				.stream()
				.content()
				.doOnNext(buffer::append)
				.doOnComplete(() -> persistGeneratedReply(chatRoomId, userMessageId, buffer.toString()));
	}

	public EditAndResendResponse editAndResend(String chatRoomId, String userMessageId, String newContent) {
		validateChatRoomExists(chatRoomId);
		Message newUserMessage = createEditedUserMessage(chatRoomId, userMessageId, newContent);
		List<Message> ancestorChain = messageBranchService.getAncestorChain(chatRoomId, newUserMessage.getId());

		String content = plainChatClient.prompt()
				.messages(toAiMessages(ancestorChain))
				.call()
				.content();

		Message assistantMessage = messageBranchService.appendMessage(chatRoomId, newUserMessage.getId(),
				MessageRole.ASSISTANT, content);
		return toEditAndResendResponse(chatRoomId, newUserMessage.getId(), assistantMessage.getId());
	}

	public Flux<String> editAndResendStream(String chatRoomId, String userMessageId, String newContent) {
		validateChatRoomExists(chatRoomId);
		Message newUserMessage = createEditedUserMessage(chatRoomId, userMessageId, newContent);
		List<Message> ancestorChain = messageBranchService.getAncestorChain(chatRoomId, newUserMessage.getId());

		StringBuilder buffer = new StringBuilder();
		return plainChatClient.prompt()
				.messages(toAiMessages(ancestorChain))
				.stream()
				.content()
				.doOnNext(buffer::append)
				.doOnComplete(() -> persistGeneratedReply(chatRoomId, newUserMessage.getId(), buffer.toString()));
	}

	private void persistGeneratedReply(String chatRoomId, String parentMessageId, String content) {
		try {
			messageBranchService.appendMessage(chatRoomId, parentMessageId, MessageRole.ASSISTANT, content);
		} catch (Exception e) {
			// 스트림은 이미 클라이언트에 완료 신호를 보낸 뒤라 여기서 실패해도 되돌릴 수 없다.
			// 예외를 던지면 Reactor가 완료 신호를 조용히 폐기(onErrorDropped)할 뿐이라 로그로 남긴다.
			log.error("재생성/편집 응답 저장에 실패했습니다. chatRoomId={}, parentMessageId={}", chatRoomId, parentMessageId, e);
		}
	}

	private Message createEditedUserMessage(String chatRoomId, String userMessageId, String newContent) {
		List<Message> originalChain = messageBranchService.getAncestorChain(chatRoomId, userMessageId);
		validateUserMessage(originalChain);
		Message original = originalChain.getLast();
		Message parent = original.getParentMessage();
		String parentId = (parent == null) ? null : parent.getId();
		return messageBranchService.appendMessage(chatRoomId, parentId, MessageRole.USER, newContent);
	}

	private void validateUserMessage(List<Message> ancestorChain) {
		if (ancestorChain.getLast().getRole() != MessageRole.USER) {
			throw new InvalidBranchTargetException();
		}
	}

	private List<org.springframework.ai.chat.messages.Message> toAiMessages(List<Message> chain) {
		return chain.stream().map(this::toAiMessage).toList();
	}

	private org.springframework.ai.chat.messages.Message toAiMessage(Message message) {
		return switch (message.getRole()) {
			case USER -> new UserMessage(message.getContent());
			case ASSISTANT -> new AssistantMessage(message.getContent());
		};
	}

	private MessageResponse toMessageResponse(String chatRoomId, String messageId) {
		return findBranchNode(chatRoomId, messageId).map(MessageResponse::from)
				.orElseThrow(MessageNotFoundException::new);
	}

	private EditAndResendResponse toEditAndResendResponse(String chatRoomId, String userMessageId,
			String assistantMessageId) {
		List<BranchNode> activePath = messageBranchService.getActivePathWithSiblingInfo(chatRoomId);
		MessageResponse userMessage = findBranchNode(activePath, userMessageId).map(MessageResponse::from)
				.orElseThrow(MessageNotFoundException::new);
		MessageResponse assistantMessage = findBranchNode(activePath, assistantMessageId).map(MessageResponse::from)
				.orElseThrow(MessageNotFoundException::new);
		return new EditAndResendResponse(userMessage, assistantMessage);
	}

	private Optional<BranchNode> findBranchNode(String chatRoomId, String messageId) {
		return findBranchNode(messageBranchService.getActivePathWithSiblingInfo(chatRoomId), messageId);
	}

	private Optional<BranchNode> findBranchNode(List<BranchNode> activePath, String messageId) {
		return activePath.stream().filter(node -> node.message().getId().equals(messageId)).findFirst();
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
