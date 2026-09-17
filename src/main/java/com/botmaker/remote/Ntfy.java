package com.botmaker.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Background push with no push service of our own: an <a href="https://ntfy.sh">ntfy</a> topic.
 *
 * <p>The app gets a foreground event over its socket; a phone in a pocket does not, and running a push
 * service for one operator is the wrong size of answer. ntfy is a free app plus one URL — the operator
 * subscribes the phone to a topic, gives the same URL to {@code --ntfy}, and every {@code waiting} event
 * lands as a notification. Best effort, fire and forget: a topic that does not answer costs nothing here.
 */
public final class Ntfy {

    private final Optional<URI> topic;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Ntfy(Optional<URI> topic) {
        this.topic = topic;
    }

    public boolean configured() {
        return topic.isPresent();
    }

    public void send(String title, String body) {
        if (topic.isEmpty()) return;
        HttpRequest request = HttpRequest.newBuilder(topic.get())
                .timeout(Duration.ofSeconds(10))
                .header("Title", title)
                .header("Tags", "robot")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
    }
}
