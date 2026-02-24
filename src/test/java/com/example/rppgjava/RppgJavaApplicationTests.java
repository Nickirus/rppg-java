package com.example.rppgjava;

import com.example.rppg.app.RppgJavaApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RppgJavaApplicationTests {

    @Test
    void mainRuns() {
        assertDoesNotThrow(() -> RppgJavaApplication.main(new String[0]));
    }
}