package com.example.springaichat.common.exception;

import com.example.springaichat.common.dto.response.ApiResponse;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

@Log4j2
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String INTERNAL_SERVER_ERROR_MESSAGE = "서버 오류가 발생했습니다";

	@ExceptionHandler(BusinessException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleBusinessException(BusinessException e) {
		return ApiResponse.error(e.getMessage());
	}

	@ExceptionHandler(WebExchangeBindException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleValidationException(WebExchangeBindException e) {
		String message = e.getFieldErrors().stream()
				.map(DefaultMessageSourceResolvable::getDefaultMessage)
				.collect(Collectors.joining(", "));
		return ApiResponse.error(message);
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Void> handleException(Exception e) {
		log.error("Unexpected error occurred", e);
		return ApiResponse.error(INTERNAL_SERVER_ERROR_MESSAGE);
	}

}
