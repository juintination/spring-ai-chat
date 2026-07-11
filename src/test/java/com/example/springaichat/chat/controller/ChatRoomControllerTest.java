package com.example.springaichat.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springaichat.chat.dto.request.CreateChatRoomRequest;
import com.example.springaichat.chat.dto.request.SwitchBranchRequest;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatRoomControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private MessageRepository messageRepository;

	@AfterEach
	void cleanUp() {
		messageRepository.deleteAllInBatch();
		chatRoomRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("채팅방을 생성한다")
	void createChatRoom() {
		CreateChatRoomRequest request = new CreateChatRoomRequest("새 대화");

		webTestClient.post().uri("/api/chat-rooms")
				.bodyValue(request)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.title").isEqualTo("새 대화")
				.jsonPath("$.data.id").exists();
	}

	@Test
	@DisplayName("제목이 비어있으면 채팅방 생성에 실패한다")
	void createChatRoom_blankTitle() {
		CreateChatRoomRequest request = new CreateChatRoomRequest(" ");

		webTestClient.post().uri("/api/chat-rooms")
				.bodyValue(request)
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.success").isEqualTo(false);
	}

	@Test
	@DisplayName("채팅방 목록을 최신순으로 조회한다")
	void getChatRooms() {
		ChatRoom first = chatRoomRepository.save(ChatRoom.builder().title("첫 번째").build());
		ChatRoom second = chatRoomRepository.save(ChatRoom.builder().title("두 번째").build());

		webTestClient.get().uri("/api/chat-rooms")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.length()").isEqualTo(2)
				.jsonPath("$.data[0].id").isEqualTo(second.getId())
				.jsonPath("$.data[1].id").isEqualTo(first.getId());
	}

	@Test
	@DisplayName("채팅방의 활성 경로 메시지 목록을 등록 순서대로 조회한다")
	void getMessages() {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("대화").build());
		Message userMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom)
				.role(MessageRole.USER)
				.content("안녕")
				.build());
		Message assistantMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom)
				.role(MessageRole.ASSISTANT)
				.content("안녕하세요")
				.parentMessage(userMessage)
				.build());
		chatRoom.updateActiveLeafMessageId(assistantMessage.getId());
		chatRoomRepository.save(chatRoom);

		webTestClient.get().uri("/api/chat-rooms/{chatRoomId}/messages", chatRoom.getId())
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.length()").isEqualTo(2)
				.jsonPath("$.data[0].id").isEqualTo(userMessage.getId())
				.jsonPath("$.data[0].content").isEqualTo("안녕")
				.jsonPath("$.data[1].id").isEqualTo(assistantMessage.getId());
	}

	@Test
	@DisplayName("형제 브랜치로 전환하면 활성 경로가 갱신된다")
	void switchBranch() {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("대화").build());
		Message userMessage = messageRepository.save(Message.builder()
				.chatRoom(chatRoom)
				.role(MessageRole.USER)
				.content("질문")
				.build());
		Message firstAnswer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom)
				.role(MessageRole.ASSISTANT)
				.content("첫 번째 답변")
				.parentMessage(userMessage)
				.build());
		Message secondAnswer = messageRepository.save(Message.builder()
				.chatRoom(chatRoom)
				.role(MessageRole.ASSISTANT)
				.content("두 번째 답변")
				.parentMessage(userMessage)
				.build());
		chatRoom.updateActiveLeafMessageId(secondAnswer.getId());
		chatRoomRepository.save(chatRoom);

		webTestClient.post().uri("/api/chat-rooms/{chatRoomId}/active-branch", chatRoom.getId())
				.bodyValue(new SwitchBranchRequest(firstAnswer.getId()))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.length()").isEqualTo(2)
				.jsonPath("$.data[1].id").isEqualTo(firstAnswer.getId())
				.jsonPath("$.data[1].siblingIndex").isEqualTo(1)
				.jsonPath("$.data[1].siblingCount").isEqualTo(2);
	}

	@Test
	@DisplayName("존재하지 않는 채팅방의 메시지를 조회하면 400 에러를 반환한다")
	void getMessages_chatRoomNotFound() {
		webTestClient.get().uri("/api/chat-rooms/{chatRoomId}/messages", "0000000000000")
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.success").isEqualTo(false)
				.jsonPath("$.message").isEqualTo("채팅방을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("채팅방을 삭제하면 목록에서 제외된다")
	void deleteChatRoom() {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("삭제될 대화").build());

		webTestClient.delete().uri("/api/chat-rooms/{chatRoomId}", chatRoom.getId())
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.success").isEqualTo(true);

		assertThat(chatRoomRepository.existsById(chatRoom.getId())).isFalse();
	}

	@Test
	@DisplayName("존재하지 않는 채팅방을 삭제하면 400 에러를 반환한다")
	void deleteChatRoom_chatRoomNotFound() {
		webTestClient.delete().uri("/api/chat-rooms/{chatRoomId}", "0000000000000")
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.success").isEqualTo(false)
				.jsonPath("$.message").isEqualTo("채팅방을 찾을 수 없습니다");
	}

}
