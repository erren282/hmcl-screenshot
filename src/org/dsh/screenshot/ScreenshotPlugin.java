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

    /// The side bar that received the injected entry.
    private Object injectedSideBar;

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

    /// Keeps the tab bound to whichever instance the page currently shows.
    ///
    /// <p>The instance page holds the current instance in a private `instanceReference` property.
    /// Observing it is the only way for an injected tab to learn about switches, since the
    /// launcher's own `GameInstanceLoadable` dispatch happens when a tab's node is created — and
    /// this node is created once and reused.</p>
    ///
    /// @param instancePage the instance page
    /// @param page         the screenshot page to keep in sync
    private void watchInstanceReference(Node instancePage, ScreenshotPage page) {
        try {
            Object reference = readField(instancePage, "instanceReference");
            if (!(reference instanceof javafx.beans.property.ObjectProperty<?> property)) {
                getContext().logWarning("无法监听实例切换：instanceReference 不可用");
                return;
            }

            // Registered through the ObjectProperty interface with an explicit ChangeListener
            // type, so the overload resolution cannot pick the InvalidationListener variant.
            javafx.beans.value.ChangeListener<Object> listener = (observable, oldValue, newValue) ->
                    applyInstance(page, newValue);
            ((javafx.beans.property.ObjectProperty<Object>) property).addListener(listener);

            getContext().logInfo("已监听实例切换");
        } catch (Throwable e) {
            getContext().logWarning("无法监听实例切换：" + e);
        }
    }

    /// Binds the screenshot page to an `InstanceReference` value.
    ///
    /// @param page  the screenshot page
    /// @param value the new reference, or `null`
    private void applyInstance(ScreenshotPage page, Object value) {
        if (value == null)
            return;
        try {
            Object repository = value.getClass().getMethod("repository").invoke(value);
            Object instanceId = value.getClass().getMethod("instanceId").invoke(value);
            page.loadInstance(
                    repository instanceof HMCLGameRepository repo ? repo : null,
                    instanceId instanceof String id ? id : null);
            getContext().logInfo("截图页已切换到实例：" + instanceId);
        } catch (Throwable e) {
            getContext().logWarning("切换实例后刷新截图失败：" + e);
        }
    }

    /// Adds the tab to the instance page's side bar and binds it to the current instance.
    ///
    /// <p>The instance page does not render a horizontal tab strip: it builds an
    /// `AdvancedListBox` side bar where each entry calls
    /// `addNavigationDrawerTab(tabHeader, tab, title, icon)`. Registering the tab in the header
    /// alone leaves it invisible, because nothing creates a side-bar row for it. Both steps are
    /// therefore performed here.</p>
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
        page.loadInstance(readRepository(instancePage), readVersion(instancePage));

        // Follow later version switches. The launcher only invokes `loadInstance` through
        // `loadVersionFor(...)`, i.e. when it builds a tab's node; a page created once and reused
        // therefore kept showing the first instance. Watching `instanceReference` is what keeps
        // this tab in sync.
        watchInstanceReference(instancePage, page);

        var tabClass = Class.forName("org.jackhuang.hmcl.ui.construct.TabControl$Tab");
        Object tab = tabClass.getConstructor(String.class).newInstance(TAB_ID);
        tabClass.getMethod("setText", String.class).invoke(tab, "截图管理");
        tabClass.getMethod("setNode", Node.class).invoke(tab, page);

        Object sideBar = findSideBar(instancePage);
        if (sideBar == null) {
            getContext().logWarning("未找到实例页侧边栏，标签页无法显示");
            return;
        }

        // Both registrations are required, and they are independent:
        //  - the tab must sit in the header's list, otherwise selecting it has no content to show;
        //  - the side bar needs its own row, built by addNavigationDrawerTab, otherwise the user has
        //    nothing to click. addNavigationDrawerTab creates only the row, never the tab itself.
        @SuppressWarnings("unchecked")
        var typedTabs = (javafx.collections.ObservableList<Object>) tabs;
        typedTabs.add(tab);

        var addMethod = sideBar.getClass().getMethod("addNavigationDrawerTab",
                Class.forName("org.jackhuang.hmcl.ui.construct.TabHeader"),
                tabClass,
                String.class,
                org.jackhuang.hmcl.ui.SVG.class);
        addMethod.invoke(sideBar, header, tab, "截图管理", org.jackhuang.hmcl.ui.SVG.SCREENSHOT_MONITOR);

        hideSideBarScrollBar(sideBar);

        injectedPage = page;
        injectedHeader = header;
        injectedSideBar = sideBar;
        getContext().logInfo("已向实例管理添加「截图管理」标签页");
    }

    /// Hides the side bar's scroll bar so the added entry never introduces one.
    ///
    /// <p>`AdvancedListBox` is a `ScrollPane`, so one more row can make the launcher show a
    /// vertical scroll bar. A style class suppresses it; the list still fits because the instance
    /// page's side bar is sized to its content.</p>
    ///
    /// @param sideBar the side bar
    private static void hideSideBarScrollBar(Object sideBar) {
        if (sideBar instanceof javafx.scene.Node node) {
            if (!node.getStyleClass().contains("screenshot-no-scrollbar"))
                node.getStyleClass().add("screenshot-no-scrollbar");
        }
        if (sideBar instanceof javafx.scene.control.ScrollPane scroll) {
            scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        }
    }

    /// Finds the instance page's side bar.
    ///
    /// <p>Located by type inside the page's own subtree, so unrelated lists elsewhere in the
    /// window are never touched.</p>
    ///
    /// @param instancePage the instance page
    /// @return the side bar, or `null`
    private static Object findSideBar(Node instancePage) {
        for (Node node : allNodes(instancePage)) {
            if (node instanceof org.jackhuang.hmcl.ui.construct.AdvancedListBox)
                return node;
        }
        return null;
    }

    /// Returns a node and all its descendants.
    ///
    /// @param root the node
    /// @return the flat list
    private static java.util.List<Node> allNodes(Node root) {
        java.util.List<Node> nodes = new java.util.ArrayList<>();
        collectNodes(root, nodes);
        return nodes;
    }

    /// Recursively collects nodes.
    ///
    /// @param node the node
    /// @param into the target list
    private static void collectNodes(Node node, java.util.List<Node> into) {
        if (node == null)
            return;
        into.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                collectNodes(child, into);
        }
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
        injectedSideBar = null;
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
