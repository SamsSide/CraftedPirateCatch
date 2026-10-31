package net.samsside.craftedpiratecatch.loot;

/**
 * One pirate loot tier. {@code chance} is a percentage (already validated/scaled);
 * {@code name} is the raw MiniMessage/legacy string rendered for {@code <tier>}.
 */
public record LootTier(String id, String name, double chance, int points,
                       String trophyCommand, boolean broadcast) {

    public boolean hasTrophy() {
        return trophyCommand != null && !trophyCommand.isBlank();
    }
}
