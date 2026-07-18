package com.example.springaichat.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.service.MessageBranchService;
import com.example.springaichat.chat.tool.ChatRoomHistoryTools.ChatRoomSummary;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ChatRoomHistoryToolsTest {

    private static final String CHAT_ROOM_ID = "chat-room-id";

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageBranchService messageBranchService;

    @InjectMocks
    private ChatRoomHistoryTools chatRoomHistoryTools;

    @Test
    @DisplayName("전체 채팅방의 id와 제목 목록을 최신순으로 조회한다")
    void listChatRooms() {
        ChatRoom resume = ChatRoom.builder().id("room-1").title("이력서 작성").build();
        ChatRoom portfolio = ChatRoom.builder().id("room-2").title("포트폴리오 작성").build();
        given(chatRoomRepository.findAll(Sort.by(Sort.Direction.DESC, "id")))
            .willReturn(List.of(portfolio, resume));

        List<ChatRoomSummary> summaries = chatRoomHistoryTools.listChatRooms();

        assertThat(summaries).containsExactly(
            new ChatRoomSummary("room-2", "포트폴리오 작성"),
            new ChatRoomSummary("room-1", "이력서 작성"));
    }

    @Test
    @DisplayName("채팅방의 활성 대화 내용을 역할과 함께 텍스트로 반환한다")
    void getChatRoomHistory() {
        Message userMessage = Message.builder().role(MessageRole.USER).content("이력서 초안 작성해줘").build();
        Message assistantMessage = Message.builder().role(MessageRole.ASSISTANT).content("네, 작성해드릴게요").build();
        given(messageBranchService.getActivePath(CHAT_ROOM_ID)).willReturn(List.of(userMessage, assistantMessage));

        String history = chatRoomHistoryTools.getChatRoomHistory(CHAT_ROOM_ID);

        assertThat(history).isEqualTo("[USER] 이력서 초안 작성해줘\n[ASSISTANT] 네, 작성해드릴게요");
    }

    @Test
    @DisplayName("대화 내용이 없는 채팅방을 조회하면 안내 문구를 반환한다")
    void getChatRoomHistory_empty() {
        given(messageBranchService.getActivePath(CHAT_ROOM_ID)).willReturn(List.of());

        String history = chatRoomHistoryTools.getChatRoomHistory(CHAT_ROOM_ID);

        assertThat(history).isEqualTo("해당 채팅방에는 대화 내용이 없습니다.");
    }

}
