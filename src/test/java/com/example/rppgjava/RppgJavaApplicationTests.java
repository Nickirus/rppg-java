package com.example.rppgjava;

import com.example.rppg.RppgApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RppgApplicationTests {

    @Test
    void mainRuns() {
        assertDoesNotThrow(() -> RppgApplication.main(new String[0]));
    }
}
