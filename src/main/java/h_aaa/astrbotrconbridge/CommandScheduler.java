package h_aaa.astrbotrconbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Schedules console commands without a compile-time dependency on Paper or Folia. */
final class CommandScheduler {
    private final Plugin plugin;
    private final Object globalScheduler;
    private final Method globalExecute;

    CommandScheduler(Plugin plugin) {
        this.plugin = plugin;
        Method getter;
        try {
            getter = Bukkit.class.getMethod("getGlobalRegionScheduler");
        } catch (NoSuchMethodException ignored) {
            // Older Bukkit/Spigot servers use the main thread scheduler.
            globalScheduler = null;
            globalExecute = null;
            return;
        }

        try {
            globalScheduler = getter.invoke(null);
            globalExecute = getter.getReturnType().getMethod("execute", Plugin.class, Runnable.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot initialize the global command scheduler", e);
        }
    }

    void execute(Runnable task) {
        if (globalExecute == null) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        // Folia owns console commands on the global region; Paper/Leaves map this to the main thread.
        try {
            globalExecute.invoke(globalScheduler, plugin, task);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Cannot schedule a console command", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access the global command scheduler", e);
        }
    }
}
