package com.pmh.bridge.lsp;

/**
 * Mahony complementary filter turning the tracker's raw IMU samples into an
 * orientation quaternion.
 *
 * The PICO Swift tracker reports 16-bit gyro and accelerometer values:
 * accelerometer is roughly 2048 counts per g (a tracker at rest reads about
 * 2050 on its gravity axis), gyro is assumed to be a ±2000 dps part so one
 * degree per second is about 16.4 counts. Both are configurable in case a
 * different unit turns up.
 */
public final class ImuFusion {

    /** Accelerometer counts per g. */
    public static final float ACCEL_PER_G = 2048.0f;
    /** Gyro counts per degree/second. */
    public static final float GYRO_PER_DPS = 16.4f;
    /** Filter gain; larger trusts the accelerometer more. */
    private static final float KP = 0.5f;

    /** Samples averaged at start-up to remove the gyro bias. */
    private static final int BIAS_SAMPLES = 100;

    private float q0 = 1.0f;
    private float q1;
    private float q2;
    private float q3;

    private final float[] biasSum = new float[3];
    private int biasCount;
    private boolean biasDone;

    public synchronized void reset() {
        q0 = 1.0f;
        q1 = 0.0f;
        q2 = 0.0f;
        q3 = 0.0f;
        biasSum[0] = biasSum[1] = biasSum[2] = 0.0f;
        biasCount = 0;
        biasDone = false;
    }

    /**
     * @param dt seconds since the previous sample
     * @return true once the filter has been fed at least one sample
     */
    public synchronized boolean update(int gx, int gy, int gz,
                                       int ax, int ay, int az, float dt) {
        if (dt <= 0.0f || dt > 1.0f) {
            return false;
        }
        if (!biasDone) {
            biasSum[0] += gx;
            biasSum[1] += gy;
            biasSum[2] += gz;
            if (++biasCount >= BIAS_SAMPLES) {
                biasDone = true;
            }
            return false;
        }
        float bx = biasSum[0] / biasCount;
        float by = biasSum[1] / biasCount;
        float bz = biasSum[2] / biasCount;

        float gxr = (gx - bx) / GYRO_PER_DPS * 0.017453293f;
        float gyr = (gy - by) / GYRO_PER_DPS * 0.017453293f;
        float gzr = (gz - bz) / GYRO_PER_DPS * 0.017453293f;

        float axn = ax;
        float ayn = ay;
        float azn = az;
        float norm = (float) Math.sqrt(axn * axn + ayn * ayn + azn * azn);
        if (norm < 1.0f) {
            return false;
        }
        axn /= norm;
        ayn /= norm;
        azn /= norm;

        // Estimated direction of gravity from the current quaternion.
        float vx = 2.0f * (q1 * q3 - q0 * q2);
        float vy = 2.0f * (q0 * q1 + q2 * q3);
        float vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3;

        // Error between measured and estimated gravity, fed back as a rate.
        float ex = ayn * vz - azn * vy;
        float ey = azn * vx - axn * vz;
        float ez = axn * vy - ayn * vx;

        gxr += KP * ex;
        gyr += KP * ey;
        gzr += KP * ez;

        float h = 0.5f * dt;
        float n0 = q0 + (-q1 * gxr - q2 * gyr - q3 * gzr) * h;
        float n1 = q1 + (q0 * gxr + q2 * gzr - q3 * gyr) * h;
        float n2 = q2 + (q0 * gyr - q1 * gzr + q3 * gxr) * h;
        float n3 = q3 + (q0 * gzr + q1 * gyr - q2 * gxr) * h;

        float qnorm = (float) Math.sqrt(n0 * n0 + n1 * n1 + n2 * n2 + n3 * n3);
        if (qnorm < 1e-6f) {
            return false;
        }
        q0 = n0 / qnorm;
        q1 = n1 / qnorm;
        q2 = n2 / qnorm;
        q3 = n3 / qnorm;
        return true;
    }

    public synchronized float[] quaternion() {
        return new float[]{q0, q1, q2, q3};
    }

    /** Roll, pitch, yaw in degrees. */
    public synchronized float[] euler() {
        float sinr = 2.0f * (q0 * q1 + q2 * q3);
        float cosr = 1.0f - 2.0f * (q1 * q1 + q2 * q2);
        float roll = (float) Math.atan2(sinr, cosr);

        float sinp = 2.0f * (q0 * q2 - q3 * q1);
        float pitch = (float) Math.asin(Math.max(-1.0f, Math.min(1.0f, sinp)));

        float siny = 2.0f * (q0 * q3 + q1 * q2);
        float cosy = 1.0f - 2.0f * (q2 * q2 + q3 * q3);
        float yaw = (float) Math.atan2(siny, cosy);

        final float toDeg = 57.29578f;
        return new float[]{roll * toDeg, pitch * toDeg, yaw * toDeg};
    }
}
