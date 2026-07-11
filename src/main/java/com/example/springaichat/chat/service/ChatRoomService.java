package com.example.springaichat.chat.service;

import com.example.springaichat.chat.dto.request.CreateChatRoomRequest;
import com.example.springaichat.chat.dto.response.ChatRoomResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final MessageBranchService messageBranchService;

	@Transactional
	public ChatRoomResponse createChatRoom(CreateChatRoomRequest request) {
		ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder()
				.title(request.title())
				.build());
		return ChatRoomResponse.from(chatRoom);
	}

	public List<ChatRoomResponse> getChatRooms() {
		return chatRoomRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
				.map(ChatRoomResponse::from)
				.toList();
	}

	public List<MessageResponse> getMessages(String chatRoomId) {
		validateChatRoomExists(chatRoomId);
		return messageBranchService.getActivePathWithSiblingInfo(chatRoomId).stream()
				.map(MessageResponse::from)
				.toList();
	}

	@Transactional
	public List<MessageResponse> switchBranch(String chatRoomId, String messageId) {
		validateChatRoomExists(chatRoomId);
		messageBranchService.switchBranch(chatRoomId, messageId);
		return getMessages(chatRoomId);
	}

	@Transactional
	public void deleteChatRoom(String chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
				.orElseThrow(ChatRoomNotFoundException::new);
		chatRoomRepository.delete(chatRoom);
	}

	private void validateChatRoomExists(String chatRoomId) {
		if (!chatRoomRepository.existsById(chatRoomId)) {
			throw new ChatRoomNotFoundException();
		}
	}

}
