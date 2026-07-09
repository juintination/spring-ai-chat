package com.example.springaichat.chat.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured Output용 DTO BeanOutputConverter가 이 record로부터 JSON 스키마를 생성해 프롬프트에 포함시키고, 모델 응답을 다시 이 타입으로 역직렬화한다.
 */
public record ChatRoomTitle(
    @JsonPropertyDescription("채팅방 제목. 한국어, 15자 이내, 핵심 주제만 간결하게")
    String title
) {

}
