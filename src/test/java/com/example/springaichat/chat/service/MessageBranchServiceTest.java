package com.example.springaichat.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageBranchServiceTest {

	private static final String CHAT_ROOM_ID = "chat-room-id";

	@Mock
	private ChatRoomRepository chatRoomRepository;

	@Mock
	private MessageRepository messageRepository;

	@InjectMocks
	private MessageBranchService messageBranchService;

	@Test
	@DisplayName("getAncestorChain은 조상이 많아도 최근 MEMORY_WINDOW_SIZE(20)개까지만 walk한다")
	void getAncestorChain_boundedToMemoryWindowSize() {
		ChatRoom chatRoom = ChatRoom.builder().id(CHAT_ROOM_ID).build();
		Message leaf = buildChain(chatRoom, 25);
		given(messageRepository.findById(leaf.getId())).willReturn(Optional.of(leaf));

		List<Message> result = messageBranchService.getAncestorChain(CHAT_ROOM_ID, leaf.getId());

		assertThat(result).hasSize(20);
		assertThat(result.get(0).getContent()).isEqualTo("메시지-5");
		assertThat(result.get(19).getContent()).isEqualTo("메시지-24");
	}

	@Test
	@DisplayName("getAncestorChain은 조상 수가 창 크기보다 짧으면 전부 반환한다")
	void getAncestorChain_shorterThanWindow_returnsAll() {
		ChatRoom chatRoom = ChatRoom.builder().id(CHAT_ROOM_ID).build();
		Message leaf = buildChain(chatRoom, 3);
		given(messageRepository.findById(leaf.getId())).willReturn(Optional.of(leaf));

		List<Message> result = messageBranchService.getAncestorChain(CHAT_ROOM_ID, leaf.getId());

		assertThat(result).hasSize(3);
		assertThat(result.get(0).getContent()).isEqualTo("메시지-0");
		assertThat(result.get(2).getContent()).isEqualTo("메시지-2");
	}

	private Message buildChain(ChatRoom chatRoom, int length) {
		Message current = null;
		for (int i = 0; i < length; i++) {
			current = Message.builder()
					.id("msg-" + i)
					.chatRoom(chatRoom)
					.role(MessageRole.USER)
					.content("메시지-" + i)
					.parentMessage(current)
					.build();
		}
		return current;
	}

}
