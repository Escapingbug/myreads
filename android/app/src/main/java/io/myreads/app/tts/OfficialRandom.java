package io.myreads.app.tts;

import java.math.BigInteger;

/** NumPy 1.26 PCG64 stream for default_rng(1234), shared by every chunk of one request. */
public final class OfficialRandom {
    // NumPy SeedSequence(1234) -> PCG64 initial state. Keeping the initialized
    // state avoids a second, subtly different SeedSequence implementation.
    private static final BigInteger INITIAL = new BigInteger("29299324949094424543410418505067287561");
    private static final BigInteger INCREMENT = new BigInteger("107381791681050441119675421997145146149");
    private static final BigInteger MULTIPLIER = new BigInteger("2360ed051fc65da44385df649fccf645", 16);
    private static final BigInteger MASK = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    private BigInteger state = INITIAL;
    private long draws;

    public OfficialRandom copy() {
        OfficialRandom result = new OfficialRandom(); result.state = state; result.draws = draws; return result;
    }
    public void restore(OfficialRandom saved) { state = saved.state; draws = saved.draws; }
    public Transaction transaction() { return new Transaction(this); }
    public static final class Transaction implements AutoCloseable {
        private final OfficialRandom target, saved;
        private boolean committed;
        private Transaction(OfficialRandom target) { this.target = target; this.saved = target.copy(); }
        public void commit() { committed = true; }
        @Override public void close() { if (!committed) target.restore(saved); }
    }
    public long draws() { return draws; }
    public String key() { return state.toString(16); }
    public static OfficialRandom resume(String key, long draws) {
        if (key == null || !key.matches("[0-9a-f]{1,32}") || draws < 0 || draws > 1_000_000_000_000L)
            throw new IllegalArgumentException("Invalid saved sampling state");
        OfficialRandom result = new OfficialRandom(); result.state = new BigInteger(key, 16); result.draws = draws; return result;
    }
    public void advance(long count) {
        if (count < 0 || count > 1_000_000) throw new IllegalArgumentException("Invalid random draw count");
        for (long i = 0; i < count; i++) nextDouble();
    }
    public double nextDouble() {
        state = state.multiply(MULTIPLIER).add(INCREMENT).and(MASK);
        long mixed = state.shiftRight(64).longValue() ^ state.longValue();
        long value = Long.rotateRight(mixed, state.shiftRight(122).intValue());
        draws++;
        return (value >>> 11) * 0x1.0p-53;
    }
    public float nextFloat() {
        // The exported graph receives float32 uniforms, exactly like upstream.
        return (float) Math.min(0.99999994, Math.max(0, nextDouble()));
    }
}
