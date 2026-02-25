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
            Config config = Config.defaults();
            String csvPath = argValue(args, "--csv=");
            if (csvPath != null && !csvPath.isBlank()) {
                config = config.withCsvPath(csvPath.trim());
            }
            boolean ok = RunModeProcessor.run(config);
            if (!ok) {
                System.exit(1);
            }
            return;
        }
        if (hasArg(args, "--web")) {
            boolean ok = WebUiMode.run();
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

    private static String argValue(String[] args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix) && arg.length() > prefix.length()) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
