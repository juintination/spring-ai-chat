package com.example.springaichat.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateTimeToolsTest {

    private final DateTimeTools dateTimeTools = new DateTimeTools();

    @Test
    @DisplayName("현재 날짜와 시각을 지정된 포맷의 문자열로 반환한다")
    void getCurrentDateTime_returnsFormattedNow() {
        String result = dateTimeTools.getCurrentDateTime();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (E)", Locale.KOREAN);
        String expectedPrefix = LocalDateTime.now().format(formatter).substring(0, 16);
        assertThat(result).startsWith(expectedPrefix);
    }

}
