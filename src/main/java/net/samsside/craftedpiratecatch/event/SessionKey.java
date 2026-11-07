package net.samsside.craftedpiratecatch.event;

import java.time.LocalDate;

/** One session of a rotation day: {@code session} is 0-based. */
public record SessionKey(LocalDate day, int session) {
}
