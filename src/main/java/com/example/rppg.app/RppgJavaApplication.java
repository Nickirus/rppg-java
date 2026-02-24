package com.example.rppg.app;

public final class RppgJavaApplication {
    private RppgJavaApplication() {
    }

    public static void main(String[] args) {
        if (hasArg(args, "--camera-check")) {
            boolean ok = CameraSmokeCheck.runDefaultCameraCheck();
            if (!ok) {
                System.exit(1);
            }
            return;
        }
        if (hasArg(args, "--run")) {
            boolean ok = RunModeProcessor.run(Config.defaults());
            if (!ok) {
                System.exit(1);
            }
            return;
        }
        System.out.println("rppg-java MVP skeleton");
    }

    private static boolean hasArg(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
