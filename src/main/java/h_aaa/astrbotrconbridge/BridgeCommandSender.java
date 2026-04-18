package h_aaa.astrbotrconbridge;

import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BridgeCommandSender implements ConsoleCommandSender {
    private final ConsoleCommandSender delegate;
    private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<String>();

    public BridgeCommandSender(ConsoleCommandSender delegate) {
        this.delegate = delegate;
    }

    public List<String> getLines() {
        return lines;
    }

    @Override
    public void sendMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            lines.add(message);
        }
        delegate.sendMessage(message);
    }

    @Override
    public void sendMessage(String[] messages) {
        if (messages == null) {
            return;
        }
        for (String message : messages) {
            sendMessage(message);
        }
    }

    @Override
    public Server getServer() {
        return delegate.getServer();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Spigot spigot() {
        return delegate.spigot();
    }

    @Override
    public boolean isPermissionSet(String name) {
        return delegate.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return delegate.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return delegate.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return delegate.hasPermission(perm);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return delegate.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return delegate.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return delegate.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return delegate.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        delegate.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        delegate.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Set<PermissionAttachmentInfo> permissions = delegate.getEffectivePermissions();
        if (permissions == null) {
            return Collections.emptySet();
        }
        return new HashSet<PermissionAttachmentInfo>(permissions);
    }

    @Override
    public boolean isOp() {
        return delegate.isOp();
    }

    @Override
    public void setOp(boolean value) {
        delegate.setOp(value);
    }

    @Override
    public boolean isConversing() {
        return delegate.isConversing();
    }

    @Override
    public void acceptConversationInput(String input) {
        delegate.acceptConversationInput(input);
    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        return delegate.beginConversation(conversation);
    }

    @Override
    public void abandonConversation(Conversation conversation) {
        delegate.abandonConversation(conversation);
    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
        delegate.abandonConversation(conversation, details);
    }

    @Override
    public void sendRawMessage(String message) {
        sendMessage(message);
    }

    // 低版本 Bukkit 无 UUID 重载，这里不声明 @Override 以兼容更广版本。
    public void sendMessage(UUID sender, String message) {
        sendMessage(message);
    }

    public void sendMessage(UUID sender, String[] messages) {
        sendMessage(messages);
    }

    public void sendRawMessage(UUID sender, String message) {
        sendMessage(message);
    }
}
