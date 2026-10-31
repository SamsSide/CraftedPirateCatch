package net.samsside.craftedpiratecatch.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Allocation;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Award;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Kind;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Ranked;
import net.samsside.craftedpiratecatch.reward.RewardAllocator.Summary;
import org.junit.jupiter.api.Test;

class RewardAllocatorTest {

    private static final int[] SHARDS = {5, 3, 2};

    private static Ranked p(String name, String ip, int points, long last) {
        return new Ranked(UUID.nameUUIDFromBytes(name.getBytes()), name, ip, points, last);
    }

    private static List<Award> of(Allocation a, Ranked r) {
        List<Award> out = new ArrayList<>();
        for (Award aw : a.awards()) {
            if (aw.uuid().equals(r.uuid())) {
                out.add(aw);
            }
        }
        return out;
    }

    private static void assertOnly(Allocation a, Ranked r, Kind kind, int amount) {
        List<Award> list = of(a, r);
        assertEquals(1, list.size(), r.name() + " awards: " + list);
        assertEquals(kind, list.get(0).kind());
        assertEquals(amount, list.get(0).amount());
    }

    private final Ranked a = p("Alice", "1.1.1.1", 50, 100);
    private final Ranked b = p("Bob", "2.2.2.2", 30, 100);
    private final Ranked c = p("Cara", "3.3.3.3", 20, 100);
    private final Ranked d = p("Dan", "4.4.4.4", 5, 100);
    private final Ranked e = p("Eve", "5.5.5.5", 1, 100);

    @Test
    void normalDay() {
        Allocation al = RewardAllocator.allocate(List.of(c, e, a, d, b), Set.of(), 3, SHARDS, 1);
        assertEquals(Summary.KEY_AWARDED, al.summary());
        assertEquals(a.uuid(), al.keyRecipient().uuid());
        assertOnly(al, a, Kind.KEY, 1);
        assertOnly(al, b, Kind.PLACEMENT_SHARDS, 3);
        assertOnly(al, c, Kind.PLACEMENT_SHARDS, 2);
        assertOnly(al, d, Kind.PARTICIPATION_SHARDS, 1);
        assertOnly(al, e, Kind.PARTICIPATION_SHARDS, 1);
    }

    @Test
    void topOnCooldown() {
        Allocation al = RewardAllocator.allocate(List.of(a, b, c, d), Set.of(a.uuid()), 3, SHARDS, 1);
        assertEquals(Summary.KEY_AWARDED, al.summary());
        assertEquals(b.uuid(), al.keyRecipient().uuid());
        List<Award> alice = of(al, a);
        assertEquals(2, alice.size());
        assertTrue(alice.stream().anyMatch(x -> x.kind() == Kind.PLACEMENT_SHARDS && x.amount() == 5));
        assertTrue(alice.stream().anyMatch(x -> x.kind() == Kind.COOLDOWN_NOTE));
        assertOnly(al, b, Kind.KEY, 1);
        assertOnly(al, c, Kind.PLACEMENT_SHARDS, 2);
        assertOnly(al, d, Kind.PARTICIPATION_SHARDS, 1);
    }

    @Test
    void turnoutBelowMinimum() {
        Ranked alt = p("AliceAlt", "1.1.1.1", 10, 100);
        Allocation al = RewardAllocator.allocate(List.of(a, b, alt), Set.of(), 3, SHARDS, 1);
        assertEquals(Summary.NO_KEY_TURNOUT, al.summary());
        assertNull(al.keyRecipient());
        assertOnly(al, a, Kind.PLACEMENT_SHARDS, 5);
        assertOnly(al, b, Kind.PLACEMENT_SHARDS, 3);
        assertOnly(al, alt, Kind.PLACEMENT_SHARDS, 2);
    }

    @Test
    void allOnCooldown() {
        Allocation al = RewardAllocator.allocate(List.of(a, b, c), Set.of(a.uuid(), b.uuid(), c.uuid()), 3, SHARDS, 1);
        assertEquals(Summary.NO_ELIGIBLE, al.summary());
        assertNull(al.keyRecipient());
        assertTrue(of(al, a).stream().anyMatch(x -> x.kind() == Kind.PLACEMENT_SHARDS && x.amount() == 5));
        assertTrue(of(al, a).stream().anyMatch(x -> x.kind() == Kind.COOLDOWN_NOTE));
        assertOnly(al, b, Kind.PLACEMENT_SHARDS, 3);
        assertOnly(al, c, Kind.PLACEMENT_SHARDS, 2);
    }

    @Test
    void keyRecipientOutsideTopThree() {
        Allocation al = RewardAllocator.allocate(List.of(a, b, c, d, e),
                Set.of(a.uuid(), b.uuid(), c.uuid()), 3, SHARDS, 1);
        assertEquals(d.uuid(), al.keyRecipient().uuid());
        assertTrue(of(al, a).stream().anyMatch(x -> x.kind() == Kind.PLACEMENT_SHARDS && x.amount() == 5));
        assertOnly(al, b, Kind.PLACEMENT_SHARDS, 3);
        assertOnly(al, c, Kind.PLACEMENT_SHARDS, 2);
        assertOnly(al, d, Kind.KEY, 1);
        assertOnly(al, e, Kind.PARTICIPATION_SHARDS, 1);
    }

    @Test
    void singlePlayerFailsTurnout() {
        Allocation al = RewardAllocator.allocate(List.of(a), Set.of(), 3, SHARDS, 1);
        assertEquals(Summary.NO_KEY_TURNOUT, al.summary());
        assertOnly(al, a, Kind.PLACEMENT_SHARDS, 5);
    }

    @Test
    void singlePlayerWithLowMinimumGetsKey() {
        Allocation al = RewardAllocator.allocate(List.of(a), Set.of(), 1, SHARDS, 1);
        assertEquals(Summary.KEY_AWARDED, al.summary());
        assertOnly(al, a, Kind.KEY, 1);
    }

    @Test
    void emptyList() {
        Allocation al = RewardAllocator.allocate(List.of(), Set.of(), 3, SHARDS, 1);
        assertEquals(Summary.NOBODY, al.summary());
        assertTrue(al.awards().isEmpty());
    }

    @Test
    void zeroPointPlayersAreNeverRanked() {
        Ranked zero = p("Zed", "9.9.9.9", 0, 1);
        Allocation al = RewardAllocator.allocate(List.of(zero), Set.of(), 1, SHARDS, 1);
        assertEquals(Summary.NOBODY, al.summary());
    }

    @Test
    void tiesGoToEarlierScorer() {
        Ranked early = p("Zoe", "1.1.1.1", 20, 1_000);
        Ranked late = p("Adam", "2.2.2.2", 20, 2_000);
        Ranked third = p("Cid", "3.3.3.3", 10, 500);
        Allocation al = RewardAllocator.allocate(List.of(late, third, early), Set.of(), 3, SHARDS, 1);
        assertEquals(early.uuid(), al.ranked().get(0).uuid());
        assertEquals(late.uuid(), al.ranked().get(1).uuid());
        assertOnly(al, early, Kind.KEY, 1);
        assertOnly(al, late, Kind.PLACEMENT_SHARDS, 3);
    }
}
