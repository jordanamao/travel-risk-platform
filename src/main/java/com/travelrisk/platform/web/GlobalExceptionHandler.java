package com.travelrisk.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised while handling a request to {@code {"error": "message"}}.
 * Extends {@link ResponseEntityExceptionHandler} so standard Spring MVC errors (405, 415, ...)
 * keep their status codes but use the same body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String SERVER_ERROR = "Unexpected server error. Please try again.";

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ApiError> notFound(ResourceNotFoundException error) {
    return respond(HttpStatus.NOT_FOUND, error.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
    return respond(HttpStatus.BAD_REQUEST, error.getMessage());
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiError> unauthorized(AuthenticationException error) {
    return respond(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(AccessDeniedException error) {
    return respond(HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> serverError(Exception error, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), error);
    String message = error.getMessage() != null && error.getMessage().contains("429")
        ? "External geocoding service is rate-limiting requests. Try a listed city or wait a moment before retrying."
        : SERVER_ERROR;
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, message);
  }

  /** Bean Validation failures, e.g. "Origin is required. Date is required." */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException error, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    // Report fields in the order the request type declares them, not validation order.
    List<String> declared = Arrays.stream(error.getParameter().getParameterType().getDeclaredFields())
        .map(Field::getName)
        .toList();
    List<String> messages = error.getBindingResult().getFieldErrors().stream()
        .sorted(Comparator.comparingInt(fieldError -> declared.indexOf(fieldError.getField())))
        .map(GlobalExceptionHandler::sentence)
        .distinct()
        .toList();
    String message = messages.isEmpty() ? "Request is invalid." : messages.stream().collect(Collectors.joining(" "));
    return body(HttpStatus.BAD_REQUEST, message);
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException error, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return body(HttpStatus.BAD_REQUEST, capitalize(error.getParameterName()) + " is required.");
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException error, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return body(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON.");
  }

  /** Every other standard Spring MVC exception: keep its status, use our body. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception error, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    String message;
    if (status.is5xxServerError()) {
      message = SERVER_ERROR;
    } else if (error instanceof ResponseStatusException statusError && statusError.getReason() != null) {
      message = statusError.getReason();
    } else {
      message = defaultMessage(status);
    }
    return ResponseEntity.status(status).headers(headers).body(new ApiError(message));
  }

  private static String defaultMessage(HttpStatus status) {
    return switch (status) {
      case NOT_FOUND -> "No endpoint matches this path.";
      case METHOD_NOT_ALLOWED -> "This HTTP method is not supported for this path.";
      case UNSUPPORTED_MEDIA_TYPE -> "Content type is not supported. Send application/json.";
      default -> status.getReasonPhrase() + ".";
    };
  }

  private static String sentence(FieldError fieldError) {
    return capitalize(fieldError.getField()) + " " + fieldError.getDefaultMessage() + ".";
  }

  private static String capitalize(String value) {
    return value == null || value.isEmpty() ? "Request" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static ResponseEntity<ApiError> respond(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(message));
  }

  private static ResponseEntity<Object> body(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(message));
  }
}
