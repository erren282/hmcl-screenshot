/*
 * 截图管理插件 —— 入口
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.screenshot;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.ui.Controllers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Adds a 截图管理 tab to the launcher's instance page.
///
/// <p>The plugin API has no hook for injecting tabs into the instance page, so the tab is added
/// by walking the running UI: when an instance page appears, a tab is appended to its tab header.
/// Nothing is patched permanently — disabling the plugin removes the tab again.</p>
///
/// <p>All launcher internals are reached reflectively and by class name. The instance page and its
/// tab control are not part of the plugin API, so a hard reference would make this plugin fail to
/// load on any launcher version that reshapes them.</p>
public final class ScreenshotPlugin extends HmclPlugin {

    /// Class name of the launcher's instance page.
    private static final String INSTANCE_PAGE_CLASS = "org.jackhuang.hmcl.ui.versions.VersionPage";

    /// Style class used to recognise the tab we injected.
    private static final String TAB_ID = "dsh-screenshot-tab";

    /// Classpath location of the bundled style sheet.
    private static final String STYLE_RESOURCE = "/org/dsh/screenshot/screenshot.css";

    /// File name of the extracted style sheet.
    private static final String STYLE_FILE = "screenshot.css";

    /// Poll interval while waiting for the launcher window to come up.
    private static final int POLL_MILLIS = 400;

    /// How many poll rounds to wait before giving up.
    private static final int MAX_POLL_ROUNDS = 60;

    private String styleUrl;

    /// The page currently injected into an instance page, if any.
    private ScreenshotPage injectedPage;

    /// The tab header that received the injected tab.
    private Object injectedHeader;

    /// Whether a poll for newly opened instance pages is running.
    private boolean watching;

    @Override
    public void onEnable() {
        installStyles();
        startWatching();
        getContext().logInfo("截图管理已启用：实例管理中将出现「截图管理」标签页");
    }

    @Override
    public void onDisable() {
        removeStyles();
        removeInjectedTab();
        getContext().logInfo("截图管理已停用");
    }

    // ------------------------------------------------------------------- watch

    /// Starts polling for instance pages that need the extra tab.
    ///
    /// <p>Polling is used rather than an event hook because the launcher offers no notification
    /// when an instance page is shown, and the page is rebuilt on every navigation.</p>
    private void startWatching() {
        if (watching)
            return;
        watching = true;
        poll(0);
    }

    /// Runs one poll round and schedules the next while the plugin is enabled.
    ///
    /// @param round the number of rounds already completed
    private void poll(int round) {
        Platform.runLater(() -> {
            if (!watching)
                return;

            boolean found = injectIntoVisibleInstancePage();

            // Keep polling while the window is still being built, then settle into a slow beat so
            // a newly opened instance page still receives its tab.
            int delay = found || round > MAX_POLL_ROUNDS ? 1200 : POLL_MILLIS;
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(delay));
            pause.setOnFinished(event -> poll(round + 1));
            pause.play();
        });
    }

    /// Appends the screenshot tab to the instance page currently on screen.
    ///
    /// @return whether an instance page was found
    private boolean injectIntoVisibleInstancePage() {
        try {
            var scene = Controllers.getScene();
            if (scene == null)
                return false;

            Node instancePage = findByName(scene.getRoot(), INSTANCE_PAGE_CLASS);
            if (instancePage == null)
                return false;

            Object header = readField(instancePage, "tab");
            if (header == null)
                return false;

            // Already injected into this very header: nothing to do.
            if (header == injectedHeader && injectedPage != null)
                return true;

            removeInjectedTab();
            addTab(instancePage, header);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /// Adds the tab to a tab header and binds it to the page's current instance.
    ///
    /// @param instancePage the instance page
    /// @param header       its tab header
    private void addTab(Node instancePage, Object header) throws Exception {
        var getTabs = header.getClass().getMethod("getTabs");
        Object tabsObject = getTabs.invoke(header);
        if (!(tabsObject instanceof javafx.collections.ObservableList<?> tabs))
            return;

        // Never duplicate the tab if a previous poll already added one.
        for (Object existing : tabs) {
            if (existing != null && TAB_ID.equals(invokeString(existing, "getId")))
                return;
        }

        ScreenshotPage page = new ScreenshotPage();
        page.bind(readRepository(instancePage), readVersion(instancePage));

        var tabClass = Class.forName("org.jackhuang.hmcl.ui.construct.TabControl$Tab");
        var constructor = tabClass.getConstructor(String.class, String.class);
        Object tab = constructor.newInstance(TAB_ID, "截图管理");

        tabClass.getMethod("setNode", Node.class).invoke(tab, page);

        @SuppressWarnings("unchecked")
        var typedTabs = (javafx.collections.ObservableList<Object>) tabs;
        typedTabs.add(tab);

        injectedPage = page;
        injectedHeader = header;
        getContext().logInfo("已向实例管理添加「截图管理」标签页");
    }

    /// Removes the injected tab, if the header still holds it.
    private void removeInjectedTab() {
        if (injectedHeader == null) {
            injectedPage = null;
            return;
        }

        try {
            Object tabsObject = injectedHeader.getClass().getMethod("getTabs").invoke(injectedHeader);
            if (tabsObject instanceof javafx.collections.ObservableList<?> tabs) {
                Object stale = null;
                for (Object existing : tabs) {
                    if (existing != null && TAB_ID.equals(invokeString(existing, "getId"))) {
                        stale = existing;
                        break;
                    }
                }
                if (stale != null) {
                    @SuppressWarnings("unchecked")
                    var typedTabs = (javafx.collections.ObservableList<Object>) tabs;
                    typedTabs.remove(stale);
                }
            }
        } catch (Throwable ignored) {
            // The page may already be gone.
        }

        injectedPage = null;
        injectedHeader = null;
    }

    // ---------------------------------------------------------------- instance

    /// Reads the repository of the instance page.
    ///
    /// @param instancePage the instance page
    /// @return the repository, or `null`
    private static HMCLGameRepository readRepository(Node instancePage) {
        try {
            Object value = instancePage.getClass().getMethod("getRepository").invoke(instancePage);
            return value instanceof HMCLGameRepository repository ? repository : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /// Reads the version name of the instance page.
    ///
    /// @param instancePage the instance page
    /// @return the version, or `null`
    private static String readVersion(Node instancePage) {
        try {
            Object value = instancePage.getClass().getMethod("getVersion").invoke(instancePage);
            return value instanceof String version ? version : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reflect

    /// Calls a string-returning no-argument method, tolerating failure.
    ///
    /// @param target the receiver
    /// @param method the method name
    /// @return the result, or `null`
    private static String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof String text ? text : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /// Reads a field by name, allowing access to non-public fields.
    ///
    /// @param target the receiver
    /// @param field  the field name
    /// @return the value, or `null`
    private static Object readField(Object target, String field) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var declared = type.getDeclaredField(field);
                declared.setAccessible(true);
                return declared.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    /// Finds the first descendant whose class matches a name.
    ///
    /// @param root      the node to search from
    /// @param className the fully qualified class name
    /// @return the node, or `null`
    private static Node findByName(Node root, String className) {
        if (root == null)
            return null;
        if (className.equals(root.getClass().getName()))
            return root;
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findByName(child, className);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- styles

    /// Extracts the bundled style sheet and attaches it to the scene root.
    private void installStyles() {
        var context = getContext();
        Path target = context.getDataFile(STYLE_FILE);

        try (InputStream input = ScreenshotPlugin.class.getResourceAsStream(STYLE_RESOURCE)) {
            if (input == null) {
                context.logWarning("样式表资源缺失：" + STYLE_RESOURCE);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            styleUrl = target.toUri().toString();
        } catch (IOException e) {
            context.logWarning("无法释放样式表：" + e);
            return;
        }

        Runnable apply = () -> {
            Parent root = sceneRoot();
            if (root == null || styleUrl == null)
                return;
            if (!root.getStylesheets().contains(styleUrl))
                root.getStylesheets().add(styleUrl);
        };

        if (Platform.isFxApplicationThread())
            apply.run();
        else
            Platform.runLater(apply);
    }

    /// Removes the style sheet from the scene root.
    private void removeStyles() {
        Runnable remove = () -> {
            Parent root = sceneRoot();
            if (root != null && styleUrl != null)
                root.getStylesheets().remove(styleUrl);
        };

        if (Platform.isFxApplicationThread())
            remove.run();
        else
            Platform.runLater(remove);
    }

    /// Returns the launcher's scene root, or `null` while the window is still being built.
    ///
    /// @return the root node
    private static Parent sceneRoot() {
        try {
            return Controllers.getScene() == null ? null : Controllers.getScene().getRoot();
        } catch (Throwable e) {
            return null;
        }
    }
}
