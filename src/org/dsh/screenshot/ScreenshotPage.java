/*
 * 截图管理插件 —— 截图页面
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.screenshot;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The screenshot browser shown as a tab inside the instance page.
///
/// <p>Captures are square tiles: the picture fills a fixed box and a "⋮" button in its lower-left
/// corner opens a menu with the per-file actions. The page scrolls as a whole, so the grid is
/// never clipped and no separate scroller competes with it.</p>
final class ScreenshotPage extends VBox
        implements org.jackhuang.hmcl.ui.versions.VersionPage.GameInstanceLoadable {

    /// Tile edge length in pixels.
    private static final double TILE_SIZE = 168;

    /// Three-dot glyph for the per-tile menu, drawn small so it stays out of the way.
    private static final String MORE_VERT_PATH =
            "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z"
                    + "M12 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
                    + "M12 16c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z";

    private final FlowPane grid = new FlowPane(12, 12);
    private final Label summary = new Label();

    /// Every screenshot found for the instance.
    private final List<Path> screenshots = new ArrayList<>();

    private HMCLGameRepository repository;
    private String version;

    ScreenshotPage() {
        getStyleClass().add("screenshot-page");
        setSpacing(0);

        getChildren().addAll(buildToolbar(), buildScrollArea());
    }

    @Override
    public void loadInstance(HMCLGameRepository repository, String version) {
        this.repository = repository;
        this.version = version;
        reload();
    }

    // -------------------------------------------------------------------- layout

    /// Builds the toolbar: two action buttons and the counts on the right.
    ///
    /// <p>Plain buttons, not navigation rows: these run an action, so the launcher's trailing
    /// chevron would wrongly imply a page transition.</p>
    ///
    /// @return the toolbar
    private Node buildToolbar() {
        StackPane refresh = actionButton("刷新", SVG.REFRESH, this::reload);
        StackPane openFolder = actionButton("打开截图目录", SVG.FOLDER_OPEN, this::openDirectory);

        summary.getStyleClass().add("screenshot-summary");

        HBox actions = new HBox(8, refresh, openFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(12, actions, spacer(), summary);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("screenshot-toolbar");
        return row;
    }

    /// Builds the scrollable tile area.
    ///
    /// @return the scroll pane
    private Node buildScrollArea() {
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setPadding(new Insets(14));

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("screenshot-scroll");
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    // ---------------------------------------------------------------------- data

    /// Re-reads the screenshots from disk.
    void reload() {
        screenshots.clear();
        grid.getChildren().clear();

        if (repository == null || version == null || version.isBlank()) {
            summary.setText("未选择实例");
            return;
        }

        List<Path> directories = ScreenshotLocator.candidateDirectories(repository, version);
        screenshots.addAll(ScreenshotLocator.collect(directories));

        summary.setText(screenshots.isEmpty()
                ? "「" + version + "」没有截图"
                : "「" + version + "」共 " + screenshots.size() + " 张");

        for (Path screenshot : screenshots)
            grid.getChildren().add(buildTile(screenshot));

        if (screenshots.isEmpty()) {
            Label empty = new Label("游戏内按 F2 截图后点「刷新」即可显示。");
            empty.getStyleClass().add("screenshot-hint");
            grid.getChildren().add(empty);
        }
    }

    // --------------------------------------------------------------------- tiles

    /// Builds one square tile for a capture.
    ///
    /// @param screenshot the image file
    /// @return the tile
    private Node buildTile(Path screenshot) {
        ImageView view = new ImageView();
        try {
            // Decoded straight at tile size: full-resolution captures would waste memory here.
            view.setImage(new Image(screenshot.toUri().toString(),
                    TILE_SIZE, TILE_SIZE, true, true, false));
        } catch (Throwable ignored) {
            // A broken image leaves an empty box rather than failing the page.
        }
        view.setFitWidth(TILE_SIZE);
        view.setFitHeight(TILE_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        StackPane frame = new StackPane(view);
        frame.getStyleClass().add("screenshot-tile");
        frame.setPrefSize(TILE_SIZE, TILE_SIZE);
        frame.setMinSize(TILE_SIZE, TILE_SIZE);
        frame.setMaxSize(TILE_SIZE, TILE_SIZE);
        frame.setOnMouseClicked(event -> ScreenshotPreview.show(screenshot));

        Node options = optionsButton(screenshot);
        StackPane.setAlignment(options, Pos.BOTTOM_LEFT);
        StackPane.setMargin(options, new Insets(0, 0, 7, 7));

        StackPane tile = new StackPane(frame, options);
        tile.setPrefSize(TILE_SIZE, TILE_SIZE);
        tile.setMinSize(TILE_SIZE, TILE_SIZE);
        tile.setMaxSize(TILE_SIZE, TILE_SIZE);
        return tile;
    }

    /// Builds the "⋮" button and the native popup menu it opens.
    ///
    /// <p>Uses the launcher's own `PopupMenu` + `IconedMenuItem` + `JFXPopup` — the same trio every
    /// other menu in the launcher uses — so styling and behaviour match instead of looking like a
    /// stock JavaFX context menu.</p>
    ///
    /// @param screenshot the image file
    /// @return the button
    private Node optionsButton(Path screenshot) {
        // A deliberately tiny glyph: it must be discoverable without covering the picture.
        // Scaled down in code because SVGPath ignores the CSS scale properties.
        javafx.scene.shape.SVGPath dots = new javafx.scene.shape.SVGPath();
        dots.setContent(MORE_VERT_PATH);
        dots.getStyleClass().add("screenshot-options-dots");
        dots.setScaleX(0.42);
        dots.setScaleY(0.42);

        StackPane button = new StackPane(dots);
        button.getStyleClass().add("screenshot-options");
        button.setPickOnBounds(true);

        org.jackhuang.hmcl.ui.construct.PopupMenu menu = new org.jackhuang.hmcl.ui.construct.PopupMenu();
        com.jfoenix.controls.JFXPopup popup = new com.jfoenix.controls.JFXPopup(menu);

        menu.getContent().setAll(
                new org.jackhuang.hmcl.ui.construct.IconedMenuItem(SVG.SCREENSHOT_MONITOR,
                        "用图片管理器打开", () -> openImage(screenshot), popup),
                new org.jackhuang.hmcl.ui.construct.IconedMenuItem(SVG.FOLDER_OPEN,
                        "在文件管理器中显示", () -> reveal(screenshot), popup),
                new org.jackhuang.hmcl.ui.construct.IconedMenuItem(SVG.EDIT,
                        "重命名", () -> rename(screenshot), popup),
                new org.jackhuang.hmcl.ui.construct.IconedMenuItem(SVG.DELETE,
                        "删除", () -> delete(screenshot), popup));

        button.setOnMouseClicked(event -> {
            popup.show(button, com.jfoenix.controls.JFXPopup.PopupVPosition.BOTTOM,
                    com.jfoenix.controls.JFXPopup.PopupHPosition.LEFT);
            event.consume();
        });
        return button;
    }

    // ------------------------------------------------------------------ actions

    /// Opens one image in the system's default viewer.
    ///
    /// @param screenshot the image file
    private void openImage(Path screenshot) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(screenshot.toFile());
                return;
            }
        } catch (Throwable ignored) {
            // Fall back to revealing the folder.
        }
        try {
            FXUtils.openFolder(screenshot.getParent());
        } catch (Throwable ignored) {
            // Nothing else to try.
        }
    }

    /// Renames a capture, preserving its extension.
    ///
    /// <p>Uses the launcher's own prompt — `Controllers.prompt`, the same dialog the instance
    /// rename uses — so the styling and behaviour are identical instead of a hand-built form.</p>
    ///
    /// @param screenshot the image file
    private void rename(Path screenshot) {
        String current = screenshot.getFileName().toString();
        int dot = current.lastIndexOf('.');
        String baseName = dot > 0 ? current.substring(0, dot) : current;
        String extension = dot > 0 ? current.substring(dot) : "";

        Controllers.prompt("重命名截图", (value, handler) -> {
            String error = applyRename(screenshot, value, extension);
            if (error == null)
                handler.resolve();
            else
                handler.reject(error);
        }, baseName);
    }

    /// Performs the actual rename on disk.
    ///
    /// @param screenshot the original file
    /// @param entered    the name typed by the user
    /// @param extension  the original extension, re-appended so the viewer still recognises it
    /// @return `null` on success, or a message to show in the prompt
    private String applyRename(Path screenshot, String entered, String extension) {
        if (entered == null || entered.isBlank())
            return "文件名不能为空";

        String name = entered.strip();
        // Drop a re-typed extension, then always append the original one.
        if (!extension.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT)))
            name = name.substring(0, name.length() - extension.length()).strip();
        if (name.isEmpty())
            return "文件名不能为空";

        Path target = screenshot.resolveSibling(name + extension);
        if (target.equals(screenshot))
            return null; // Nothing to do, but not an error.

        try {
            if (Files.exists(target))
                return "已存在同名文件：" + target.getFileName();

            Files.move(screenshot, target);
            reload();
            return null;
        } catch (Throwable e) {
            return "重命名失败：" + e.getMessage();
        }
    }

    /// Deletes a capture after confirmation.
    ///
    /// @param screenshot the image file
    private void delete(Path screenshot) {
        Controllers.confirm("确定删除这张截图吗？", screenshot.getFileName().toString(),
                MessageDialogPane.MessageType.WARNING, () -> {
                    try {
                        Files.deleteIfExists(screenshot);
                        reload();
                    } catch (Throwable e) {
                        summary.setText("删除失败：" + e);
                    }
                }, () -> {
                });
    }

    /// Opens the containing folder with the file selected.
    ///
    /// <p>`FXUtils.openFolder` only opens a directory, so the platform's own "reveal" invocation is
    /// used: Explorer's `/select,` on Windows, `open -R` on macOS, and the folder elsewhere.</p>
    ///
    /// @param screenshot the image file
    private void reveal(Path screenshot) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + screenshot.toAbsolutePath())
                        .start();
                return;
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", screenshot.toAbsolutePath().toString()).start();
                return;
            }
            // Linux file managers differ too much to target one; showing the folder is enough.
            FXUtils.openFolder(screenshot.getParent());
        } catch (Throwable e) {
            summary.setText("无法定位文件：" + e);
        }
    }

    /// Opens the folder holding this instance's screenshots.
    private void openDirectory() {
        if (repository == null || version == null) {
            summary.setText("未选择实例");
            return;
        }

        List<Path> directories = ScreenshotLocator.candidateDirectories(repository, version);
        Path target = ScreenshotLocator.preferredDirectory(directories);
        if (target == null) {
            summary.setText("没有找到截图目录");
            return;
        }

        try {
            Files.createDirectories(target);
            FXUtils.openFolder(target);
        } catch (Throwable e) {
            summary.setText("无法打开目录：" + e);
        }
    }

    // ----------------------------------------------------------------- widgets

    /// Builds a flat icon-and-label button without a trailing chevron.
    ///
    /// @param text   the label
    /// @param icon   the icon
    /// @param action the click action
    /// @return the button
    private static StackPane actionButton(String text, SVG icon, Runnable action) {
        Label label = new Label(text);
        label.getStyleClass().add("screenshot-button-text");

        StackPane iconBox = new StackPane(icon.createIcon());
        iconBox.setMouseTransparent(true);

        HBox content = new HBox(6, iconBox, label);
        content.setAlignment(Pos.CENTER);
        content.setMouseTransparent(true);

        StackPane button = new StackPane(content);
        button.getStyleClass().add("screenshot-button");
        button.setOnMouseClicked(event -> action.run());
        return button;
    }

    /// Creates a flexible spacer.
    ///
    /// @return the spacer
    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
