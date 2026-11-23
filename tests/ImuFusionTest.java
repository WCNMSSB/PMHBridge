package com.pmh.bridge.lsp;

/**
 * Desktop test for {@link ImuFusion}. Runs without Android.
 *
 *   javac -cp build/module/classes -d build/tests tests/ImuFusionTest.java
 *   java  -cp build/module/classes;build/tests com.pmh.bridge.lsp.ImuFusionTest
 *
 * Accelerometer convention: at rest the sensor reports +1 g along the axis that
 * points up, so a tracker lying flat reports about (0, 0, +2048).
 */
public final class ImuFusionTest {

    private static int failures;

    public static void main(String[] args) {
        flat();
        tilted45();
        stationaryWithBias();
        rollChangesWithXRate();

        if (failures == 0) {
            System.out.println("ALL PASS");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }

    /** Lying flat: no tilt. */
    private static void flat() {
        ImuFusion fusion = new ImuFusion();
        for (int i = 0; i < 400; i++) {
            fusion.update(0, 0, 0, 0, 0, 2048, 0.01f);
        }
        float[] e = fusion.euler();
        check("flat roll", Math.abs(e[0]) < 2.0f, e[0]);
        check("flat pitch", Math.abs(e[1]) < 2.0f, e[1]);
    }

    /** Rolled 45 degrees: the filter must converge to about 45, not drift. */
    private static void tilted45() {
        ImuFusion fusion = new ImuFusion();
        int ax = (int) (2048 * Math.sin(Math.toRadians(45)));
        int az = (int) (2048 * Math.cos(Math.toRadians(45)));
        for (int i = 0; i < 800; i++) {
            fusion.update(0, 0, 0, 0, -ax, az, 0.01f);
        }
        float[] e = fusion.euler();
        check("tilt45 |roll| ~ 45", Math.abs(Math.abs(e[0]) - 45.0f) < 8.0f, e[0]);
    }

    /** Constant gyro bias must be cancelled by the start-up calibration. */
    private static void stationaryWithBias() {
        ImuFusion fusion = new ImuFusion();
        for (int i = 0; i < 3000; i++) {
            fusion.update(60, 0, 0, 0, 0, 2048, 0.01f);
        }
        float[] e = fusion.euler();
        check("bias roll drift", Math.abs(e[0]) < 8.0f, e[0]);
        check("bias pitch drift", Math.abs(e[1]) < 8.0f, e[1]);
    }

    /** 30 deg/s about X for 0.5 s must move roll by roughly 15 degrees. */
    private static void rollChangesWithXRate() {
        ImuFusion fusion = new ImuFusion();
        for (int i = 0; i < 200; i++) {
            fusion.update(0, 0, 0, 0, 0, 2048, 0.01f);
        }
        float before = fusion.euler()[0];
        for (int i = 0; i < 50; i++) {
            fusion.update(492, 0, 0, 0, 0, 2048, 0.01f);
        }
        float delta = fusion.euler()[0] - before;
        check("roll delta ~ 15", Math.abs(Math.abs(delta) - 15.0f) < 8.0f, delta);
    }

    private static void check(String name, boolean ok, float value) {
        System.out.printf("%-24s %s  (%.2f)%n", name, ok ? "PASS" : "FAIL", value);
        if (!ok) {
            failures++;
        }
    }
}
