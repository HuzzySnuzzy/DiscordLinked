package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerGroup;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Groups of players who share their microcredits. A player is in one group at most. Everything members earn by
 * selling goes into the group's balance and any member can exchange it (see {@link Database} changeMicro); the
 * leader names the group, invites and removes members, hands the lead on and sees the log of who earned and spent
 * what. On the microcredit leaderboard a group is one place.
 *
 * <p>Money moves fairly: joining brings your microcredits in, leaving or being removed takes an equal share
 * ({@code balance / members}) out, and ending a group (by its leader, or an admin taking it down) splits the whole
 * balance equally.
 */
public final class Groups {

    private static final int MAX_NAME = 32;

    /** What a request did, with a message for the website. */
    public record Result(boolean ok, String message) {
        static Result fail(String message) {
            return new Result(false, message);
        }

        static Result done(String message) {
            return new Result(true, message);
        }
    }

    private final DiscordLinked plugin;

    Groups(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    private PluginConfig config() {
        return plugin.config();
    }

    /** Whether new groups and members are allowed. Existing groups keep working either way. */
    public boolean open() {
        return config().marketOpen() && config().groupsEnabled();
    }

    public List<PlayerGroup> all() throws SQLException {
        return plugin.database().groups();
    }

    public Optional<PlayerGroup> of(UUID uuid) throws SQLException {
        return plugin.database().groupOf(uuid);
    }

    // ---- Players ----

    public Result create(LinkedAccount me, String name, String actor) throws SQLException {
        if (!open()) {
            return Result.fail("Groups are switched off right now.");
        }
        String n = cleanName(name);
        if (n == null) {
            return Result.fail("A group name is 1 to " + MAX_NAME + " characters.");
        }
        if (plugin.database().groupNameTaken(n, 0)) {
            return Result.fail("There's already a group called " + n + ".");
        }
        long id = plugin.database().createGroup(me.uuid(), me.minecraftName(), n);
        if (id < 0) {
            return Result.fail("You're in a group already. Leave it first.");
        }
        audit(me.uuid(), me.minecraftName(), me.discordId(), "made the group " + n, actor);
        return Result.done("Made " + n + ". Invite players to share your " + config().microNamePlural() + ".");
    }

    public Result invite(LinkedAccount me, String playerName, String actor) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("Only a group's leader can invite players.");
        }
        if (!open()) {
            return Result.fail("Groups are switched off right now.");
        }
        PlayerGroup g = group.get();
        Optional<LinkedAccount> target = plugin.database().findByName(playerName == null ? "" : playerName.strip());
        if (target.isEmpty()) {
            return Result.fail("Nobody linked is called " + playerName + ". Players need a linked account to join.");
        }
        LinkedAccount t = target.get();
        int max = config().groupMaxMembers();
        if (max > 0 && g.members().size() >= max) {
            return Result.fail(g.name() + " is full (" + max + " members).");
        }
        if (!plugin.database().inviteToGroup(g.id(), t.uuid(), me.minecraftName())) {
            return Result.fail(plugin.database().groupOf(t.uuid()).isPresent()
                    ? t.minecraftName() + " is in a group already." : t.minecraftName() + " is invited already.");
        }
        audit(t.uuid(), t.minecraftName(), t.discordId(), "was invited to " + g.name() + " by " + me.minecraftName(), actor);
        tell(t.uuid(), "minecraft.group.invited", "group", g.name(), "player", me.minecraftName());
        return Result.done("Invited " + t.minecraftName() + ". They accept on the website.");
    }

    public Result cancelInvite(LinkedAccount me, UUID uuid) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty() || !plugin.database().deleteGroupInvite(group.get().id(), uuid)) {
            return Result.fail("There's no such invitation.");
        }
        return Result.done("Invitation taken back.");
    }

    public Result accept(LinkedAccount me, long groupId, String actor) throws SQLException {
        if (!open()) {
            return Result.fail("Groups are switched off right now.");
        }
        Optional<PlayerGroup> group = plugin.database().group(groupId);
        if (group.isEmpty()) {
            return Result.fail("That group doesn't exist any more.");
        }
        int max = config().groupMaxMembers();
        if (max > 0 && group.get().members().size() >= max) {
            return Result.fail(group.get().name() + " is full.");
        }
        if (!plugin.database().acceptGroupInvite(groupId, me.uuid(), me.minecraftName())) {
            return Result.fail(of(me.uuid()).isPresent() ? "You're in a group already. Leave it first."
                    : "That invitation isn't there any more.");
        }
        PlayerGroup g = group.get();
        audit(me.uuid(), me.minecraftName(), me.discordId(), "joined " + g.name(), actor);
        for (PlayerGroup.Member m : g.members()) {
            tell(m.uuid(), "minecraft.group.joined", "group", g.name(), "player", me.minecraftName());
        }
        return Result.done("You joined " + g.name() + ". Your " + config().microNamePlural() + " are the group's now.");
    }

    public Result decline(LinkedAccount me, long groupId) throws SQLException {
        return plugin.database().deleteGroupInvite(groupId, me.uuid())
                ? Result.done("Invitation declined.") : Result.fail("That invitation isn't there any more.");
    }

    /** Leaves the group with an equal share. A leader with others in the group hands the lead on first. */
    public Result leave(LinkedAccount me, String actor) throws SQLException {
        Optional<PlayerGroup> group = of(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("You're not in a group.");
        }
        PlayerGroup g = group.get();
        if (g.isLeader(me.uuid())) {
            if (g.members().size() > 1) {
                return Result.fail("Make someone else the leader first, or end the group.");
            }
            return disband(g, me.minecraftName() + " ended it", actor, "ended " + g.name());
        }
        long share = plugin.database().leaveGroup(g.id(), me.uuid(), me.minecraftName(), "LEAVE", "left the group", actor);
        if (share < 0) {
            return Result.fail("You're not in that group.");
        }
        audit(me.uuid(), me.minecraftName(), me.discordId(), "left " + g.name() + " with " + config().micro(share), actor);
        tell(g.leader(), "minecraft.group.left", "group", g.name(), "player", me.minecraftName(), "amount", config().micro(share));
        return Result.done("You left " + g.name() + " and took your share: " + config().micro(share) + ".");
    }

    public Result remove(LinkedAccount me, UUID uuid, String actor) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("Only a group's leader can remove members.");
        }
        PlayerGroup g = group.get();
        Optional<PlayerGroup.Member> member = g.members().stream().filter(m -> m.uuid().equals(uuid)).findFirst();
        if (member.isEmpty() || g.isLeader(uuid)) {
            return Result.fail("They're not a member you can remove.");
        }
        String name = member.get().name();
        long share = plugin.database().leaveGroup(g.id(), uuid, name, "REMOVED", "removed by " + me.minecraftName(), actor);
        if (share < 0) {
            return Result.fail("They're not in the group any more.");
        }
        audit(uuid, name, null, "was removed from " + g.name() + " by " + me.minecraftName() + " with " + config().micro(share), actor);
        tell(uuid, "minecraft.group.removed", "group", g.name(), "amount", config().micro(share));
        return Result.done("Removed " + name + ". They took their share: " + config().micro(share) + ".");
    }

    public Result makeLeader(LinkedAccount me, UUID uuid, String actor) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("Only the leader can hand the lead on.");
        }
        PlayerGroup g = group.get();
        Optional<PlayerGroup.Member> member = g.members().stream().filter(m -> m.uuid().equals(uuid)).findFirst();
        if (member.isEmpty() || g.isLeader(uuid)) {
            return Result.fail("Pick another member.");
        }
        plugin.database().setGroupLeader(g.id(), uuid, member.get().name(), me.uuid(), me.minecraftName());
        audit(uuid, member.get().name(), null, "is now the leader of " + g.name(), actor);
        tell(uuid, "minecraft.group.leader", "group", g.name());
        return Result.done(member.get().name() + " leads " + g.name() + " now.");
    }

    public Result rename(LinkedAccount me, String name, String actor) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("Only the leader can rename the group.");
        }
        PlayerGroup g = group.get();
        String n = cleanName(name);
        if (n == null) {
            return Result.fail("A group name is 1 to " + MAX_NAME + " characters.");
        }
        if (plugin.database().groupNameTaken(n, g.id())) {
            return Result.fail("There's already a group called " + n + ".");
        }
        plugin.database().renameGroup(g.id(), n, me.uuid(), me.minecraftName(), g.name());
        audit(me.uuid(), me.minecraftName(), me.discordId(), "renamed " + g.name() + " to " + n, actor);
        return Result.done("Renamed to " + n + ".");
    }

    /** The leader ends their group; the balance is split equally across the members. */
    public Result end(LinkedAccount me, String actor) throws SQLException {
        Optional<PlayerGroup> group = ledBy(me.uuid());
        if (group.isEmpty()) {
            return Result.fail("Only the leader can end the group.");
        }
        PlayerGroup g = group.get();
        return disband(g, me.minecraftName() + " ended it", actor, "ended " + g.name());
    }

    // ---- Admins ----

    /** An admin takes a group down; the balance is split equally across the members. */
    public Result takeDown(long groupId, String reason, String actor) throws SQLException {
        Optional<PlayerGroup> group = plugin.database().group(groupId);
        if (group.isEmpty()) {
            return Result.fail("That group doesn't exist any more.");
        }
        PlayerGroup g = group.get();
        String why = reason == null || reason.isBlank() ? "" : ": " + reason.strip();
        return disband(g, "an admin took it down" + why, actor, "took down " + g.name() + why);
    }

    private Result disband(PlayerGroup g, String why, String actor, String auditText) throws SQLException {
        List<Database.GroupShare> shares = plugin.database().disbandGroup(g.id(), why, actor);
        if (shares == null) {
            return Result.fail("That group doesn't exist any more.");
        }
        StringBuilder split = new StringBuilder();
        for (Database.GroupShare share : shares) {
            split.append(split.isEmpty() ? "" : ", ").append(share.name()).append(' ').append(config().micro(share.amount()));
            tell(share.uuid(), "minecraft.group.ended", "group", g.name(), "reason", why, "amount", config().micro(share.amount()));
        }
        plugin.audit().log(g.leader(), leaderName(g), null, EventTypes.GROUP,
                auditText + " (" + config().micro(g.balance()) + " split: " + split + ")", actor);
        return Result.done(g.name() + " ended. Its " + config().micro(g.balance()) + " were split: " + split + ".");
    }

    // ---- Helpers ----

    private Optional<PlayerGroup> ledBy(UUID uuid) throws SQLException {
        return of(uuid).filter(g -> g.isLeader(uuid));
    }

    private static String leaderName(PlayerGroup g) {
        return g.members().stream().filter(m -> g.isLeader(m.uuid())).map(PlayerGroup.Member::name).findFirst().orElse(null);
    }

    /** The name with spaces tidied, or null if it's empty, too long or has control characters. */
    static String cleanName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        return n.isEmpty() || n.length() > MAX_NAME || n.chars().anyMatch(Character::isISOControl) ? null : n;
    }

    private void audit(UUID uuid, String name, Long discordId, String text, String actor) {
        plugin.audit().log(uuid, name, discordId, EventTypes.GROUP, text, actor);
    }

    /** A chat message to a player, if they're online and the message isn't switched off. */
    private void tell(UUID uuid, String key, String... placeholders) {
        if (plugin.messages().isEmpty(key)) {
            return;
        }
        plugin.proxy().getPlayer(uuid).ifPresent(p -> p.sendMessage(plugin.messages().minecraft(key, placeholders)));
    }
}
