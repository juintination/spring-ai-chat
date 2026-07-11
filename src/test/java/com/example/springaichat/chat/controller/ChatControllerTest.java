package com.example.springaichat.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.request.EditMessageRequest;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.support.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private MessageRepository messageRepository;

	@MockitoBean
	private ChatModel chatModel;

	@AfterEach
	void cleanUp() {
		messageRepository.deleteAllInBatch();
		chatRoomRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("채팅 메시지를 보내면 AI 응답을 받고 대화가 브랜치로 저장된다")
	void chat() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("안녕하세요! 무엇을 도와드릴까요?"));
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());

		webTestClient.post().uri("/api/chat-rooms/{chatRoomId}/chat", chatRoom.getId())
				.bodyValue(new ChatRequest("안녕"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.content").isEqualTo("안녕하세요! 무엇을 도와드릴까요?");

		List<Message> messages = messageRepository.findByChatRoomIdOrderByIdAsc(chatRoom.getId());
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).getContent()).isEqualTo("안녕");
		assertThat(messages.get(1).getContent()).isEqualTo("안녕하세요! 무엇을 도와드릴까요?");
		assertThat(messages.get(0).getParentMessage()).isNull();
		assertThat(messages.get(1).getParentMessage().getId()).isEqualTo(messages.get(0).getId());

		ChatRoom updatedRoom = chatRoomRepository.findById(chatRoom.getId()).orElseThrow();
		assertThat(updatedRoom.getActiveLeafMessageId()).isEqualTo(messages.get(1).getId());
	}

	@Test
	@DisplayName("메시지가 비어있으면 채팅 요청에 실패한다")
	void chat_blankMessage() {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());

		webTestClient.post().uri("/api/chat-rooms/{chatRoomId}/chat", chatRoom.getId())
				.bodyValue(new ChatRequest(" "))
				.exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("존재하지 않는 채팅방에 메시지를 보내면 400 에러를 반환한다")
	void chat_chatRoomNotFound() {
		webTestClient.post().uri("/api/chat-rooms/{chatRoomId}/chat", "0000000000000")
				.bodyValue(new ChatRequest("안녕"))
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.message").isEqualTo("채팅방을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("스트리밍 채팅 응답은 SSE로 토큰 단위 전달되고 완료 후 대화가 브랜치로 저장된다")
	void chatStream() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		given(chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(chatResponseOf("안녕"), chatResponseOf("하세요")));
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());

		FluxExchangeResult<String> result = webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/chat/stream", chatRoom.getId())
				.bodyValue(new ChatRequest("안녕"))
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.returnResult(String.class);

		List<String> tokens = result.getResponseBody().collectList().block(Duration.ofSeconds(5));

		assertThat(tokens).containsExactly("안녕", "하세요");
		awaitMessageCount(chatRoom.getId(), 2);

		List<Message> messages = messageRepository.findByChatRoomIdOrderByIdAsc(chatRoom.getId());
		assertThat(messages.get(0).getParentMessage()).isNull();
		assertThat(messages.get(1).getParentMessage().getId()).isEqualTo(messages.get(0).getId());

		ChatRoom updatedRoom = chatRoomRepository.findById(chatRoom.getId()).orElseThrow();
		assertThat(updatedRoom.getActiveLeafMessageId()).isEqualTo(messages.get(1).getId());
	}

	@Test
	@DisplayName("재생성을 호출하면 새 형제 응답이 추가되고 이전 응답은 유지된다")
	void regenerate() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message userMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("질문").build());
		Message firstAnswer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("첫 번째 답변")
				.parentMessage(userMessage).build());
		chatRoom.updateActiveLeafMessageId(firstAnswer.getId());
		chatRoomRepository.save(chatRoom);

		given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("두 번째 답변"));

		webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/regenerate", chatRoom.getId(),
						userMessage.getId())
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.content").isEqualTo("두 번째 답변")
				.jsonPath("$.data.siblingIndex").isEqualTo(2)
				.jsonPath("$.data.siblingCount").isEqualTo(2);

		List<Message> siblings = messageRepository.findByParentMessageId(userMessage.getId());
		assertThat(siblings).hasSize(2);
		Message secondAnswer = siblings.stream()
				.filter(m -> !m.getId().equals(firstAnswer.getId()))
				.findFirst()
				.orElseThrow();
		assertThat(secondAnswer.getContent()).isEqualTo("두 번째 답변");

		ChatRoom updatedRoom = chatRoomRepository.findById(chatRoom.getId()).orElseThrow();
		assertThat(updatedRoom.getActiveLeafMessageId()).isEqualTo(secondAnswer.getId());
	}

	@Test
	@DisplayName("응답 없이 남은 유저 메시지에 재생성을 호출하면 재시도로 응답이 저장된다")
	void regenerate_retryAfterFailure() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message orphanUserMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("스트림 중간에 끊긴 질문").build());
		chatRoom.updateActiveLeafMessageId(orphanUserMessage.getId());
		chatRoomRepository.save(chatRoom);

		given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("재시도 답변"));

		webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/regenerate", chatRoom.getId(),
						orphanUserMessage.getId())
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.content").isEqualTo("재시도 답변")
				.jsonPath("$.data.siblingIndex").isEqualTo(1)
				.jsonPath("$.data.siblingCount").isEqualTo(1);

		List<Message> children = messageRepository.findByParentMessageId(orphanUserMessage.getId());
		assertThat(children).hasSize(1);
		assertThat(children.get(0).getContent()).isEqualTo("재시도 답변");
	}

	@Test
	@DisplayName("재생성 대상이 유저 메시지가 아니면 400 에러를 반환한다")
	void regenerate_invalidTarget() {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message userMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("질문").build());
		Message assistantMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("답변").parentMessage(userMessage).build());
		chatRoom.updateActiveLeafMessageId(assistantMessage.getId());
		chatRoomRepository.save(chatRoom);

		webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/regenerate", chatRoom.getId(),
						assistantMessage.getId())
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.message").isEqualTo("유저 메시지에 대해서만 재생성/편집할 수 있습니다");
	}

	@Test
	@DisplayName("유저 메시지를 편집해 재전송하면 형제 유저 메시지가 생기고 원본은 그대로 남는다")
	void editAndResend() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message originalUserMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("원래 질문").build());
		Message answer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("원래 답변")
				.parentMessage(originalUserMessage).build());
		chatRoom.updateActiveLeafMessageId(answer.getId());
		chatRoomRepository.save(chatRoom);

		given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("수정된 답변"));

		webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/edit", chatRoom.getId(),
						originalUserMessage.getId())
				.bodyValue(new EditMessageRequest("수정된 질문"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.userMessage.content").isEqualTo("수정된 질문")
				.jsonPath("$.data.assistantMessage.content").isEqualTo("수정된 답변");

		Message reloadedOriginal = messageRepository.findById(originalUserMessage.getId()).orElseThrow();
		assertThat(reloadedOriginal.getContent()).isEqualTo("원래 질문");

		List<Message> rootSiblings = messageRepository.findByChatRoomIdAndParentMessageIsNull(chatRoom.getId());
		assertThat(rootSiblings).hasSize(2);
	}

	@Test
	@DisplayName("재생성 스트림은 SSE로 응답을 전달하고 완료 후 새 형제 응답을 저장한다")
	void regenerateStream() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message userMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("질문").build());
		Message firstAnswer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("첫 번째 답변")
				.parentMessage(userMessage).build());
		chatRoom.updateActiveLeafMessageId(firstAnswer.getId());
		chatRoomRepository.save(chatRoom);

		given(chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(chatResponseOf("두"), chatResponseOf("번째 답변")));

		FluxExchangeResult<String> result = webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/regenerate/stream", chatRoom.getId(),
						userMessage.getId())
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.returnResult(String.class);

		List<String> tokens = result.getResponseBody().collectList().block(Duration.ofSeconds(5));
		assertThat(tokens).containsExactly("두", "번째 답변");

		awaitSiblingCount(userMessage.getId(), 2);
	}

	@Test
	@DisplayName("편집 재전송 스트림은 완료 후 새 유저 메시지의 자식으로 응답을 저장한다")
	void editAndResendStream() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("새 대화").build());
		Message originalUserMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.USER).content("원래 질문").build());
		Message answer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("원래 답변")
				.parentMessage(originalUserMessage).build());
		chatRoom.updateActiveLeafMessageId(answer.getId());
		chatRoomRepository.save(chatRoom);

		given(chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(chatResponseOf("수정"), chatResponseOf("된 답변")));

		FluxExchangeResult<String> result = webTestClient.post()
				.uri("/api/chat-rooms/{chatRoomId}/messages/{userMessageId}/edit/stream", chatRoom.getId(),
						originalUserMessage.getId())
				.bodyValue(new EditMessageRequest("수정된 질문"))
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.returnResult(String.class);

		List<String> tokens = result.getResponseBody().collectList().block(Duration.ofSeconds(5));
		assertThat(tokens).containsExactly("수정", "된 답변");

		awaitRootMessageCount(chatRoom.getId(), 2);
	}

	private void awaitMessageCount(String chatRoomId, int expectedCount) {
		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline) {
			if (messageRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).size() == expectedCount) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		assertThat(messageRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).hasSize(expectedCount);
	}

	private void awaitSiblingCount(String parentMessageId, int expectedCount) {
		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline) {
			if (messageRepository.findByParentMessageId(parentMessageId).size() == expectedCount) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		assertThat(messageRepository.findByParentMessageId(parentMessageId)).hasSize(expectedCount);
	}

	private void awaitRootMessageCount(String chatRoomId, int expectedCount) {
		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline) {
			if (messageRepository.findByChatRoomIdAndParentMessageIsNull(chatRoomId).size() == expectedCount) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		assertThat(messageRepository.findByChatRoomIdAndParentMessageIsNull(chatRoomId)).hasSize(expectedCount);
	}

	private ChatResponse chatResponseOf(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}

}
