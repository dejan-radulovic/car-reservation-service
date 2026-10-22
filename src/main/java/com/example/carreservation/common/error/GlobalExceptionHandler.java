package com.example.carreservation.common.error;

import com.example.carreservation.api.model.ErrorCodeDto;
import com.example.carreservation.car.CarNotFoundException;
import com.example.carreservation.car.InvalidSearchWindowException;
import com.example.carreservation.reservation.CancellationWindowClosedException;
import com.example.carreservation.reservation.InvalidBookingPeriodException;
import com.example.carreservation.reservation.ReservationAlreadyCancelledException;
import com.example.carreservation.reservation.ReservationConflictException;
import com.example.carreservation.reservation.ReservationNotFoundException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 Problem Detail with a stable {@code code} from the API contract.
 *
 * <p>400 means the request is malformed, 422 that it is well-formed but semantically invalid, and
 * 409 that it is valid but the current state of the resource forbids it.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- domain outcomes ------------------------------------------------------------------------

    @ExceptionHandler(InvalidBookingPeriodException.class)
    ProblemDetail invalidBookingPeriod(InvalidBookingPeriodException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodeDto.INVALID_BOOKING_PERIOD, "Invalid booking period", e.getMessage());
    }

    @ExceptionHandler(InvalidSearchWindowException.class)
    ProblemDetail invalidSearchWindow(InvalidSearchWindowException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodeDto.INVALID_SEARCH_WINDOW, "Invalid search window", e.getMessage());
    }

    @ExceptionHandler(CarNotFoundException.class)
    ProblemDetail carNotFound(CarNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ErrorCodeDto.CAR_NOT_FOUND, "Car not found", e.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    ProblemDetail reservationNotFound(ReservationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ErrorCodeDto.RESERVATION_NOT_FOUND, "Reservation not found", e.getMessage());
    }

    @ExceptionHandler(ReservationConflictException.class)
    ProblemDetail reservationConflict(ReservationConflictException e) {
        return problem(HttpStatus.CONFLICT, ErrorCodeDto.RESERVATION_CONFLICT, "Time slot unavailable", e.getMessage());
    }

    @ExceptionHandler(ReservationAlreadyCancelledException.class)
    ProblemDetail alreadyCancelled(ReservationAlreadyCancelledException e) {
        return problem(HttpStatus.CONFLICT, ErrorCodeDto.RESERVATION_ALREADY_CANCELLED, "Reservation already cancelled", e.getMessage());
    }

    @ExceptionHandler(CancellationWindowClosedException.class)
    ProblemDetail cancellationWindowClosed(CancellationWindowClosedException e) {
        return problem(HttpStatus.CONFLICT, ErrorCodeDto.CANCELLATION_WINDOW_CLOSED, "Cancellation window closed", e.getMessage());
    }

    // ---- concurrency and infrastructure ---------------------------------------------------------

    /** A concurrent cancellation won the optimistic lock, or a booking timed out waiting for the car's row lock. */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail concurrentModification(RuntimeException e) {
        log.info("Concurrent modification: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, ErrorCodeDto.CONCURRENT_MODIFICATION, "Concurrent modification",
                "The resource is being modified by another request; please retry");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class})
    ProblemDetail serviceUnavailable(RuntimeException e) {
        log.error("Database unavailable", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodeDto.SERVICE_UNAVAILABLE, "Service unavailable",
                "The service is temporarily unavailable; please retry later");
    }

    /** Last resort: never leak internals, but always log the full cause chain. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodeDto.INTERNAL_ERROR, "Internal error",
                "An unexpected error occurred");
    }

    // ---- request validation ---------------------------------------------------------------------

    /** Request body fields violating the contract (e.g. a missing required field). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(validationFailed(errors));
    }

    /**
     * Query and path parameters violating the contract (e.g. {@code size=500}). The generated API
     * interfaces carry {@code @Validated}, so these arrive through method validation as a
     * {@link ConstraintViolationException}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream()
                .map(violation -> fieldError(leafName(violation), violation.getMessage()))
                .sorted(Comparator.comparing(error -> error.get("field")))
                .toList();
        return validationFailed(errors);
    }

    /** Spring MVC's own method validation, used if a controller is not proxied by {@code @Validated}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(validationFailed(errors));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(validationFailed(List.of(fieldError(e.getParameterName(), "must not be null"))));
    }

    /** Unparseable JSON, or a value of the wrong type or format (e.g. a time without an offset). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "Malformed request body";
        if (e.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            detail = "Invalid value for '" + jsonPath(mismatch.getPath()) + "': " + mismatch.getOriginalMessage();
        }
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, ErrorCodeDto.MALFORMED_REQUEST, "Malformed request", detail));
    }

    /** A query or path parameter that cannot be converted (e.g. a malformed UUID or date-time). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail argumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCodeDto.MALFORMED_REQUEST, "Malformed request",
                "Invalid value for '" + e.getName() + "': '" + e.getValue() + "'");
    }

    /**
     * Everything else Spring MVC handles itself (unknown path, wrong method, unsupported media type,
     * ...): keep its status and detail, but add the contract's {@code code}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (e instanceof HttpMediaTypeNotAcceptableException) {
            // the client cannot accept problem+json either, so send no body at all
            return ResponseEntity.status(status).headers(headers).build();
        }
        // the ProblemDetail of Spring's own exceptions is only created by super, so amend its result
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", status.is5xxServerError() ? ErrorCodeDto.INTERNAL_ERROR : ErrorCodeDto.MALFORMED_REQUEST);
        }
        return response;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static ProblemDetail validationFailed(List<Map<String, String>> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCodeDto.VALIDATION_FAILED, "Invalid request",
                "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCodeDto code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + code.getValue().toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message);
    }

    private static String leafName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    private static String jsonPath(List<JsonMappingException.Reference> path) {
        return path.stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
