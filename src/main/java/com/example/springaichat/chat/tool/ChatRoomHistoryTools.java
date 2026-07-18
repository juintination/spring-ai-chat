package com.example.springaichat.chat.tool;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.service.MessageBranchService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Tool Calling용 클래스. 사용자가 다른 채팅방의 대화 내용을 참고해달라고 요청했을 때, 모델이 {@link #listChatRooms()}로 대상
 * 채팅방을 찾고 {@link #getChatRoomHistory(String)}로 그 안의 대화 내용을 가져오는 두 단계로 호출한다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomHistoryTools {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageBranchService messageBranchService;

    public record ChatRoomSummary(String id, String title) {

    }

    @Tool(description = "전체 채팅방의 id와 제목 목록을 조회한다. 사용자가 다른 채팅방의 내용을 참고/인용해달라고 요청했을 때, "
            + "제목으로 어떤 채팅방을 조회할지 찾기 위해 사용한다.")
    public List<ChatRoomSummary> listChatRooms() {
        return chatRoomRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(chatRoom -> new ChatRoomSummary(chatRoom.getId(), chatRoom.getTitle()))
                .toList();
    }

    @Tool(description = "특정 채팅방의 전체 대화 내용을 조회한다. listChatRooms로 찾은 채팅방 id를 사용해 그 안의 대화 내용을 가져올 때 사용한다.")
    public String getChatRoomHistory(@ToolParam(description = "조회할 채팅방의 id") String chatRoomId) {
        List<Message> messages = messageBranchService.getActivePath(chatRoomId);
        if (messages.isEmpty()) {
            return "해당 채팅방에는 대화 내용이 없습니다.";
        }
        return messages.stream()
                .map(message -> "[%s] %s".formatted(message.getRole(), message.getContent()))
                .collect(Collectors.joining("\n"));
    }

}
