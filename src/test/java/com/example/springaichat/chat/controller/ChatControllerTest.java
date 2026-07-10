package com.example.springaichat.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
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
	@DisplayName("채팅 메시지를 보내면 AI 응답을 받고 대화가 저장된다")
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
	@DisplayName("스트리밍 채팅 응답은 SSE로 토큰 단위 전달되고 완료 후 대화가 저장된다")
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

	private ChatResponse chatResponseOf(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}

}
