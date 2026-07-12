package com.example.springaichat.chat.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tool Calling용 클래스 {@link Tool} 어노테이션이 붙은 메서드가 ChatClient에 등록되면, 모델이 필요하다고 판단할 때 이 메서드를 직접 호출한다.
 */
@Component
public class DateTimeTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (E)",
        Locale.KOREAN);

    @Tool(description = "현재 날짜와 시각을 조회한다. 사용자가 오늘 날짜, 요일, 지금 몇 시인지 등을 물어볼 때 사용한다.")
    public String getCurrentDateTime() {
        return LocalDateTime.now().format(FORMATTER);
    }

}
