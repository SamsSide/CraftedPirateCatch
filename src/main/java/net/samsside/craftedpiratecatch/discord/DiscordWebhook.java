package net.samsside.craftedpiratecatch.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import net.samsside.craftedpiratecatch.config.PluginConfig;
import net.samsside.craftedpiratecatch.util.FlatJson;

/** Async Discord webhook post. No dependency, no retries; failures log one WARNING. */
public final class DiscordWebhook {

    private static final int MAX_CONTENT = 2000;

    private final Logger log;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DiscordWebhook(Logger log) {
        this.log = log;
    }

    public void post(PluginConfig cfg, String content) {
        if (cfg.webhookUrl.isEmpty() || content == null || content.isBlank()) {
            return;
        }
        String text = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        StringBuilder json = new StringBuilder("{\"username\":").append(FlatJson.quote(cfg.discordUsername));
        if (!cfg.discordAvatarUrl.isEmpty()) {
            json.append(",\"avatar_url\":").append(FlatJson.quote(cfg.discordAvatarUrl));
        }
        json.append(",\"content\":").append(FlatJson.quote(text)).append('}');

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(cfg.webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warning("Discord webhook-url is not a valid URL: " + e.getMessage());
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, ex) -> {
            if (ex != null) {
                log.warning("Discord webhook post failed: " + ex.getMessage());
            } else if (resp.statusCode() / 100 != 2) {
                log.warning("Discord webhook post failed with HTTP " + resp.statusCode());
            }
        });
    }
}
