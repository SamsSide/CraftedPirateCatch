package net.samsside.craftedpiratecatch.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RewardPlannerTest {
    @Test void zeroCooldownMeansNone() {
        assertEquals(Long.MAX_VALUE, RewardPlanner.cooldownSince(0, 1_000L));
        assertEquals(Long.MAX_VALUE, RewardPlanner.cooldownSince(-5, 1_000L));
    }
    @Test void positiveCooldownLooksBack() {
        long now = 1_000_000_000_000L;
        assertEquals(now - 72 * 3_600_000L, RewardPlanner.cooldownSince(72, now));
    }
}
