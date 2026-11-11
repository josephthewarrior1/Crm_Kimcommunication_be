package com.crm.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {
    private final MockMvc mvc = standaloneSetup(new ErrorRoutes())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void stalePreviewKeepsConflictStatusAndSafeReasonWithoutUnderlyingCause() throws Exception {
        mvc.perform(post("/fixture/sync-ems")).andExpect(status().isConflict())
                .andExpect(content().string("Preview berubah. Muat ulang preview sebelum sync."));
    }

    @Test void statusWithoutReasonStillUsesItsDeclaredHttpStatus() throws Exception {
        mvc.perform(get("/fixture/missing")).andExpect(status().isNotFound())
                .andExpect(content().string("404 NOT_FOUND"));
    }

    @Test void existingIllegalArgumentAndUnexpectedErrorHandlingStayUnchanged() throws Exception {
        mvc.perform(post("/fixture/invalid")).andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid destination"));
        mvc.perform(post("/fixture/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(content().string("An error occurred: Fixture generic failure"));
    }

    @RestController
    static class ErrorRoutes {
        @PostMapping("/fixture/sync-ems") void sync() {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Preview berubah. Muat ulang preview sebelum sync.",
                    new IllegalStateException("Private diagnostic must not be included in the response"));
        }

        @GetMapping("/fixture/missing") void missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        @PostMapping("/fixture/invalid") void invalid() {
            throw new IllegalArgumentException("Invalid destination");
        }

        @PostMapping("/fixture/unexpected") void unexpected() throws Exception {
            throw new Exception("Fixture generic failure");
        }
    }
}
