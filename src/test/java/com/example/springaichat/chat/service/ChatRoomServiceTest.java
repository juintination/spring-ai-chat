package com.example.springaichat.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.springaichat.chat.dto.request.CreateChatRoomRequest;
import com.example.springaichat.chat.dto.response.ChatRoomResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.service.MessageBranchService.BranchNode;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.BuilderArbitraryIntrospector;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    private static final FixtureMonkey FIXTURE_MONKEY = FixtureMonkey.builder()
        .objectIntrospector(BuilderArbitraryIntrospector.INSTANCE)
        .build();

    private static final String CHAT_ROOM_ID = "chat-room-id";

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageBranchService messageBranchService;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("채팅방을 생성하면 저장된 채팅방 정보를 반환한다")
    void createChatRoom() {
        CreateChatRoomRequest request = new CreateChatRoomRequest("새 대화");
        ChatRoom savedChatRoom = FIXTURE_MONKEY.giveMeBuilder(ChatRoom.class)
            .set("id", CHAT_ROOM_ID)
            .set("title", request.title())
            .sample();
        given(chatRoomRepository.save(any(ChatRoom.class))).willReturn(savedChatRoom);

        ChatRoomResponse response = chatRoomService.createChatRoom(request);

        assertThat(response.id()).isEqualTo(CHAT_ROOM_ID);
        assertThat(response.title()).isEqualTo(request.title());
    }

    @Test
    @DisplayName("채팅방 목록을 최신순으로 조회한다")
    void getChatRooms() {
        List<ChatRoom> chatRooms = FIXTURE_MONKEY.giveMeBuilder(ChatRoom.class).sampleList(3);
        given(chatRoomRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))).willReturn(chatRooms);

        List<ChatRoomResponse> responses = chatRoomService.getChatRooms();

        assertThat(responses).hasSize(3)
            .extracting(ChatRoomResponse::id)
            .containsExactlyElementsOf(chatRooms.stream().map(ChatRoom::getId).toList());
    }

    @Test
    @DisplayName("존재하는 채팅방의 활성 경로 메시지 목록을 조회한다")
    void getMessages() {
        Message first = Message.builder().id("msg-1").role(MessageRole.USER).content("질문").build();
        Message second = Message.builder().id("msg-2").role(MessageRole.ASSISTANT).content("답변").build();
        List<BranchNode> activePath = List.of(
            new BranchNode(first, 1, 1, List.of("msg-1")),
            new BranchNode(second, 1, 1, List.of("msg-2")));
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getActivePathWithSiblingInfo(CHAT_ROOM_ID)).willReturn(activePath);

        List<MessageResponse> responses = chatRoomService.getMessages(CHAT_ROOM_ID);

        assertThat(responses).hasSize(2)
            .extracting(MessageResponse::id)
            .containsExactly("msg-1", "msg-2");
    }

    @Test
    @DisplayName("존재하지 않는 채팅방의 메시지를 조회하면 예외가 발생한다")
    void getMessages_chatRoomNotFound() {
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(false);

        assertThatThrownBy(() -> chatRoomService.getMessages(CHAT_ROOM_ID))
            .isInstanceOf(ChatRoomNotFoundException.class);
    }

    @Test
    @DisplayName("형제 브랜치로 전환하면 갱신된 활성 경로를 반환한다")
    void switchBranch() {
        Message switched = Message.builder().id("msg-2").role(MessageRole.ASSISTANT).content("다른 답변").build();
        List<BranchNode> activePath = List.of(new BranchNode(switched, 2, 2, List.of("msg-1", "msg-2")));
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getActivePathWithSiblingInfo(CHAT_ROOM_ID)).willReturn(activePath);

        List<MessageResponse> responses = chatRoomService.switchBranch(CHAT_ROOM_ID, "msg-2");

        verify(messageBranchService).switchBranch(CHAT_ROOM_ID, "msg-2");
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo("msg-2");
    }

    @Test
    @DisplayName("채팅방을 삭제한다")
    void deleteChatRoom() {
        ChatRoom chatRoom = FIXTURE_MONKEY.giveMeBuilder(ChatRoom.class)
            .set("id", CHAT_ROOM_ID)
            .sample();
        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(chatRoom));

        chatRoomService.deleteChatRoom(CHAT_ROOM_ID);

        verify(chatRoomRepository).delete(chatRoom);
    }

    @Test
    @DisplayName("존재하지 않는 채팅방을 삭제하면 예외가 발생한다")
    void deleteChatRoom_chatRoomNotFound() {
        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomService.deleteChatRoom(CHAT_ROOM_ID))
            .isInstanceOf(ChatRoomNotFoundException.class);

        verify(chatRoomRepository, never()).delete(any());
    }

}
