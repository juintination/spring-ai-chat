package com.example.springaichat.chat.controller;

import com.example.springaichat.chat.dto.request.CreateChatRoomRequest;
import com.example.springaichat.chat.dto.request.SwitchBranchRequest;
import com.example.springaichat.chat.dto.response.ChatRoomResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.service.ChatRoomService;
import com.example.springaichat.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    public ApiResponse<ChatRoomResponse> createChatRoom(
        @Valid @RequestBody CreateChatRoomRequest request
    ) {
        return ApiResponse.success(chatRoomService.createChatRoom(request));
    }

    @GetMapping
    public ApiResponse<List<ChatRoomResponse>> getChatRooms() {
        return ApiResponse.success(chatRoomService.getChatRooms());
    }

    @GetMapping("/{chatRoomId}/messages")
    public ApiResponse<List<MessageResponse>> getMessages(
        @PathVariable String chatRoomId
    ) {
        return ApiResponse.success(chatRoomService.getMessages(chatRoomId));
    }

    @PostMapping("/{chatRoomId}/active-branch")
    public ApiResponse<List<MessageResponse>> switchBranch(
        @PathVariable String chatRoomId,
        @Valid @RequestBody SwitchBranchRequest request
    ) {
        return ApiResponse.success(chatRoomService.switchBranch(chatRoomId, request.messageId()));
    }

    @DeleteMapping("/{chatRoomId}")
    public ApiResponse<Void> deleteChatRoom(
        @PathVariable String chatRoomId
    ) {
        chatRoomService.deleteChatRoom(chatRoomId);
        return ApiResponse.success(null);
    }

}
