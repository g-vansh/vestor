package tv.lucent.home;

/**
 * A damped spring, the motion behind every focus change. Springs keep their velocity when the
 * target moves mid-flight, so fast remote presses stay fluid instead of restarting a tween.
 */
final class Spring {
    float value, target, velocity;
    private final float stiffness, damping;

    /** @param response seconds to settle, roughly; @param bounce 0 for none, up to ~0.3 */
    Spring(float value, float response, float bounce) {
        this.value = value;
        this.target = value;
        float omega = (float) (2 * Math.PI / Math.max(0.05f, response));
        this.stiffness = omega * omega;
        this.damping = 2 * (1f - Math.max(0, Math.min(0.9f, bounce))) * omega;
    }

    void to(float t) {
        target = t;
    }

    void snap(float v) {
        value = v;
        target = v;
        velocity = 0;
    }

    /** Pushes the spring without moving its target, for small "lean" effects. */
    void kick(float v) {
        velocity += v;
    }

    boolean moving() {
        return Math.abs(target - value) > 0.0005f || Math.abs(velocity) > 0.0005f;
    }

    /** Advances by {@code dt} seconds; returns whether it is still moving. */
    boolean step(float dt, boolean instant) {
        if (instant) {
            snap(target);
            return false;
        }
        if (!moving()) {
            value = target;
            velocity = 0;
            return false;
        }
        float left = Math.min(dt, 0.1f);
        while (left > 0) {
            float h = Math.min(left, 1f / 240f);
            float force = stiffness * (target - value) - damping * velocity;
            velocity += force * h;
            value += velocity * h;
            left -= h;
        }
        return moving();
    }
}
