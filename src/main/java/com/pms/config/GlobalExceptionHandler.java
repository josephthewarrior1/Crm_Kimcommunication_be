package com.pms.config;

// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.validation.FieldError;
// import org.springframework.web.bind.MethodArgumentNotValidException;
// import org.springframework.web.bind.annotation.ControllerAdvice;
// import org.springframework.web.bind.annotation.ExceptionHandler;

// import java.util.HashMap;
// import java.util.Map;

// @ControllerAdvice
// public class GlobalExceptionHandler {

//     @ExceptionHandler(MethodArgumentNotValidException.class)
//     public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
//         Map<String, Object> body = new HashMap<>();
//         Map<String, String> errors = new HashMap<>();
//         for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
//             errors.put(fe.getField(), fe.getDefaultMessage());
//         }
//         body.put("error", "Validation failed");
//         body.put("fields", errors);
//         return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
//     }
// }

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. Catch Generic Exceptions (NullPointer, IllegalArgument, etc.)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception ex, WebRequest request) {

        // Log the full stack trace to your Backend Console (IntelliJ/Terminal)
        logger.error("Unhandled Exception Occurred: ", ex);

        // Return a JSON object to the Frontend
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "Internal Server Error",
                        "message", ex.getMessage(), // This sends the exact error text
                        "path", request.getDescription(false)));
    }

    // 2. (Optional) Catch Specific Custom Exceptions if you have them
    // @ExceptionHandler(ResourceNotFoundException.class)
    // public ResponseEntity<?> handleResourceNotFound(...) { ... }
}
