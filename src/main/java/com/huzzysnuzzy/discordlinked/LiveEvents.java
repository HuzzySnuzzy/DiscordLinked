package com.huzzysnuzzy.discordlinked;

import com.huzzysnuzzy.discordlinked.storage.AuditEntry;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pushes what happens on the server to the open admin website pages as it happens. */
public final class LiveEvents {

    public interface Listener {
        /** A new audit log entry (joins, chat, admin actions, ...). */
        void onLog(AuditEntry entry);

        /** Something else about a player changed, e.g. "snapshot" when a new inventory snapshot arrives. */
        void onPlayerChange(String kind, UUID player);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Listener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    void log(AuditEntry entry) {
        listeners.forEach(l -> l.onLog(entry));
    }

    public void playerChanged(String kind, UUID player) {
        listeners.forEach(l -> l.onPlayerChange(kind, player));
    }
}
