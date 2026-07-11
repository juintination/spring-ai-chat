package com.example.springaichat.chat.controller;

import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.request.EditMessageRequest;
import com.example.springaichat.chat.dto.response.ChatResponse;
import com.example.springaichat.chat.dto.response.EditAndResendResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.service.ChatService;
import com.example.springaichat.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/chat-rooms/{chatRoomId}")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	@PostMapping("/chat")
	public Mono<ApiResponse<ChatResponse>> chat(
		@PathVariable String chatRoomId,
		@Valid @RequestBody ChatRequest request
	) {
		return Mono.fromCallable(() -> ApiResponse.success(chatService.chat(chatRoomId, request)))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chatStream(
		@PathVariable String chatRoomId,
		@Valid @RequestBody ChatRequest request
	) {
		return Flux.defer(() -> chatService.chatStream(chatRoomId, request))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/messages/{userMessageId}/regenerate")
	public Mono<ApiResponse<MessageResponse>> regenerate(
		@PathVariable String chatRoomId,
		@PathVariable String userMessageId
	) {
		return Mono.fromCallable(() -> ApiResponse.success(chatService.regenerate(chatRoomId, userMessageId)))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/messages/{userMessageId}/regenerate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> regenerateStream(
		@PathVariable String chatRoomId,
		@PathVariable String userMessageId
	) {
		return Flux.defer(() -> chatService.regenerateStream(chatRoomId, userMessageId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/messages/{userMessageId}/edit")
	public Mono<ApiResponse<EditAndResendResponse>> editAndResend(
		@PathVariable String chatRoomId,
		@PathVariable String userMessageId,
		@Valid @RequestBody EditMessageRequest request
	) {
		return Mono
			.fromCallable(() -> ApiResponse.success(chatService.editAndResend(chatRoomId, userMessageId, request.content())))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/messages/{userMessageId}/edit/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> editAndResendStream(
		@PathVariable String chatRoomId,
		@PathVariable String userMessageId,
		@Valid @RequestBody EditMessageRequest request
	) {
		return Flux.defer(() -> chatService.editAndResendStream(chatRoomId, userMessageId, request.content()))
			.subscribeOn(Schedulers.boundedElastic());
	}

}
