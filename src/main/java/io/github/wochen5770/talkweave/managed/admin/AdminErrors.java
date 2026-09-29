package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.managed.persistence.ManagedProblem;
import io.github.wochen5770.talkweave.runtime.ConfigurationProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Profile("managed")
public class AdminErrors {
    public record Error(String code, String field) { }
    @ExceptionHandler(ManagedProblem.class) ResponseEntity<Error> managed(ManagedProblem failure) {
        int status = switch (failure.code()) {
            case INVALID_INPUT -> 400;
            case NOT_FOUND -> 404;
            case UNAUTHORIZED -> 403;
            case CONFLICT, EXPIRED, BACKLOG -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).body(new Error(failure.code().name(), null));
    }
    @ExceptionHandler(ConfigurationProblem.class) ResponseEntity<Error> configuration(ConfigurationProblem failure) {
        return ResponseEntity.badRequest().body(new Error("INVALID_SETTINGS", failure.property()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid(Exception ignored) { return ResponseEntity.badRequest().body(new Error("INVALID_INPUT", null)); }
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<Error> missing(Exception ignored) {
        return ResponseEntity.status(404).body(new Error("NOT_FOUND", null));
    }
    @ExceptionHandler(Exception.class) ResponseEntity<Error> unexpected(Exception ignored) {
        return ResponseEntity.status(500).body(new Error("UNAVAILABLE", null));
    }
}
