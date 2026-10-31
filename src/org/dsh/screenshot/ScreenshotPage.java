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
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// The screenshot browser shown as a tab inside the instance page.
///
/// <p>Tiles keep the picture's own aspect ratio and a "⋮" button appears on hover with the
/// per-file actions. Clicking a tile opens the preview at that picture, and the preview can walk
/// through the whole set without closing.</p>
///
/// <p>Selection: Ctrl+click toggles one tile, Shift+click selects a range, and a plain click opens
/// the preview. Delete removes the selection after one confirmation, so cleaning up a batch no
/// longer means one dialog per file.</p>
final class ScreenshotPage extends VBox
        implements org.jackhuang.hmcl.ui.versions.VersionPage.GameInstanceLoadable {

    /// Tile edge length in pixels.
    private static final double TILE_SIZE = 168;

    /// Corner button edge: a small, fixed hit area that stays out of the picture.
    private static final double BUTTON_SIZE = 20;

    /// Three-dot glyph for the per-tile menu, drawn small so it stays out of the way.
    private static final String MORE_VERT_PATH =
            "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z"
                    + "M12 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
                    + "M12 16c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z";

    private final FlowPane grid = new FlowPane(12, 12);
    private final Label summary = new Label();

    /// Every screenshot found for the instance, newest first.
    private final List<Path> screenshots = new ArrayList<>();

    /// Tiles in display order, parallel to [#screenshots]; used for range selection and preview
    /// navigation.
    private final List<Tile> tiles = new ArrayList<>();

    /// Currently selected files.
    private final Set<Path> selection = new LinkedHashSet<>();

    /// Anchor for Shift+click range selection.
    private int selectionAnchor = -1;

    private HMCLGameRepository repository;
    private String version;

    /// The directory currently being shown, used by “打开截图目录”.
    private Path screenshotDirectory;

    private final VBox selectionBar;

    ScreenshotPage() {
        getStyleClass().add("screenshot-page");
        setSpacing(0);

        selectionBar = buildSelectionBar();

        getChildren().addAll(buildToolbar(), selectionBar, buildScrollArea());

        // Delete works whenever the page has focus, which is what users expect after selecting.
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE && !selection.isEmpty()) {
                deleteSelection();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && !selection.isEmpty()) {
                clearSelection();
                event.consume();
            }
        });
        setFocusTraversable(true);
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
    /// @return the toolbar
    private Node buildToolbar() {
        Node refresh = actionButton("刷新", SVG.REFRESH, this::reload);
        Node openFolder = actionButton("打开截图目录", SVG.FOLDER_OPEN, this::openDirectory);

        summary.getStyleClass().add("screenshot-summary");

        HBox actions = new HBox(8, refresh, openFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(12, actions, spacer(), summary);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("screenshot-toolbar");
        return row;
    }

    /// Builds the bar that appears while a selection exists.
    ///
    /// <p>Hidden (and unmanaged) when nothing is selected, so it takes no vertical space in the
    /// common case.</p>
    ///
    /// @return the bar
    private VBox buildSelectionBar() {
        Label count = new Label();
        count.getStyleClass().add("screenshot-selection-count");

        Node delete = actionButton("删除选中", SVG.DELETE_FOREVER, this::deleteSelection);
        Node clear = actionButton("取消选择", SVG.CLOSE, this::clearSelection);

        HBox content = new HBox(8, count, spacer(), delete, clear);
        content.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(content);
        bar.getStyleClass().add("screenshot-selection-bar");
        bar.setVisible(false);
        bar.setManaged(false);

        // The label is updated on every selection change.
        selectionCountLabel = count;
        return bar;
    }

    private Label selectionCountLabel;

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
        // Keep the selection only for files that still exist: a rename or delete invalidates it.
        Set<Path> previous = new LinkedHashSet<>(selection);
        selection.clear();
        selectionAnchor = -1;

        screenshots.clear();
        tiles.clear();
        grid.getChildren().clear();

        if (repository == null || version == null || version.isBlank()) {
            summary.setText("未选择实例");
            updateSelectionBar();
            return;
        }

        screenshotDirectory = ScreenshotLocator.screenshotDirectory(repository, version);
        screenshots.addAll(ScreenshotLocator.list(screenshotDirectory));

        for (Path path : screenshots) {
            if (previous.contains(path))
                selection.add(path);
        }

        summary.setText(screenshots.isEmpty()
                ? "「" + version + "」没有截图"
                : "「" + version + "」共 " + screenshots.size() + " 张");

        for (int i = 0; i < screenshots.size(); i++) {
            Path screenshot = screenshots.get(i);
            Tile tile = new Tile(screenshot, i);
            tiles.add(tile);
            grid.getChildren().add(tile.node);
        }

        if (screenshots.isEmpty()) {
            Label empty = new Label("游戏内按 F2 截图后点「刷新」即可显示。");
            empty.getStyleClass().add("screenshot-hint");
            grid.getChildren().add(empty);
        }

        updateSelectionBar();
        refreshSelectionVisuals();
    }

    // ----------------------------------------------------------------- selection

    /// Applies a click on a tile, translating modifiers into selection changes.
    ///
    /// @param index   the tile index
    /// @param control whether Ctrl/Cmd was held
    /// @param shift   whether Shift was held
    private void onTileClicked(int index, boolean control, boolean shift) {
        Path path = screenshots.get(index);

        if (shift && selectionAnchor >= 0) {
            // Range selection from the anchor, inclusive, in either direction.
            int from = Math.min(selectionAnchor, index);
            int to = Math.max(selectionAnchor, index);
            selection.clear();
            for (int i = from; i <= to; i++)
                selection.add(screenshots.get(i));
        } else if (control) {
            if (!selection.remove(path))
                selection.add(path);
            selectionAnchor = index;
        } else {
            // A plain click opens the preview; selection stays as the preview's starting point.
            selection.clear();
            selectionAnchor = index;
        }

        updateSelectionBar();
        refreshSelectionVisuals();
    }

    /// Clears the selection.
    private void clearSelection() {
        selection.clear();
        selectionAnchor = -1;
        updateSelectionBar();
        refreshSelectionVisuals();
    }

    /// Updates the selection bar's visibility and text.
    private void updateSelectionBar() {
        boolean has = !selection.isEmpty();
        selectionBar.setVisible(has);
        selectionBar.setManaged(has);
        if (has && selectionCountLabel != null)
            selectionCountLabel.setText("已选择 " + selection.size() + " 张");
    }

    /// Repaints every tile to reflect the current selection.
    private void refreshSelectionVisuals() {
        for (Tile tile : tiles)
            tile.setSelected(selection.contains(tile.path));
    }

    // --------------------------------------------------------------------- tiles

    /// One screenshot tile, keeping the picture's aspect ratio.
    private final class Tile {

        final Path path;
        final int index;
        final StackPane node;

        /// Whether the preview image has been decoded.
        boolean loaded;

        /// Where the image goes once decoded.
        final ImageView view = new ImageView();

        final StackPane frame;

        Tile(Path path, int index) {
            this.path = path;
            this.index = index;

            view.setPreserveRatio(true);
            view.setSmooth(true);

            frame = new StackPane(view);
            frame.getStyleClass().add("screenshot-tile");
            // Placeholder size until the picture is decoded; replaced once its aspect is known.
            frame.setPrefSize(TILE_SIZE, TILE_SIZE);
            frame.setMinSize(TILE_SIZE, TILE_SIZE);
            frame.setMaxSize(TILE_SIZE, TILE_SIZE);

            Node options = optionsButton(path);
            StackPane.setAlignment(options, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(options, new Insets(0, 4, 4, 0));

            StackPane wrapper = new StackPane(frame, options);
            wrapper.setPrefSize(TILE_SIZE, TILE_SIZE);
            wrapper.setMinSize(TILE_SIZE, TILE_SIZE);
            wrapper.setMaxSize(TILE_SIZE, TILE_SIZE);

            // Hidden until the pointer is over the tile, so the button never covers the picture.
            options.setVisible(false);
            wrapper.setOnMouseEntered(event -> options.setVisible(true));
            wrapper.setOnMouseExited(event -> options.setVisible(false));

            // Decoded up front: deferring it to hover left the first paint showing empty boxes,
            // which read as broken tiles until the pointer happened to cross each one.
            loadImage();

            wrapper.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY)
                    return;
                onTileClicked(index, event.isControlDown() || event.isMetaDown(), event.isShiftDown());

                // Opening only happens when the click was not a modifier click: Ctrl/Shift are
                // about selecting, and opening a dialog on top of them would be surprising.
                if (!event.isControlDown() && !event.isMetaDown() && !event.isShiftDown()) {
                    ScreenshotPreview.show(screenshots, index);
                }
                event.consume();
            });

            node = wrapper;
        }

        /// Decodes the picture and sizes the tile to its aspect ratio.
        ///
        /// <p>Called once, when the tile is built: decoding at display size keeps the cost small
        /// even for large captures, but waiting for a hover made the grid look empty.</p>
        void loadImage() {
            if (loaded)
                return;
            loaded = true;

            try {
                // Decoded straight at tile size: full-resolution captures would waste memory here.
                Image image = new Image(path.toUri().toString(), TILE_SIZE, TILE_SIZE, true, true, false);
                view.setImage(image);

                double naturalWidth = image.getWidth() <= 0 ? TILE_SIZE : image.getWidth();
                double naturalHeight = image.getHeight() <= 0 ? TILE_SIZE : image.getHeight();
                double scale = Math.min(TILE_SIZE / naturalWidth, TILE_SIZE / naturalHeight);
                double tileWidth = Math.max(1, naturalWidth * scale);
                double tileHeight = Math.max(1, naturalHeight * scale);

                view.setFitWidth(tileWidth);
                view.setFitHeight(tileHeight);

                // The tile takes the picture's own aspect ratio: a square tile letterboxed a 16:9
                // capture, and the empty bands read as a thick border.
                frame.setPrefSize(tileWidth, tileHeight);
                frame.setMinSize(tileWidth, tileHeight);
                frame.setMaxSize(tileWidth, tileHeight);
                node.setPrefSize(tileWidth, tileHeight);
                node.setMinSize(tileWidth, tileHeight);
                node.setMaxSize(tileWidth, tileHeight);
            } catch (Throwable ignored) {
                // A broken image leaves an empty box rather than failing the page.
            }
        }

        /// Shows or hides the selected outline.
        ///
        /// @param selected whether this tile is selected
        void setSelected(boolean selected) {
            if (selected) {
                if (!frame.getStyleClass().contains("screenshot-tile-selected"))
                    frame.getStyleClass().add("screenshot-tile-selected");
            } else {
                frame.getStyleClass().remove("screenshot-tile-selected");
            }
        }
    }

    /// Builds the "⋮" button and the native popup menu it opens.
    ///
    /// @param screenshot the image file
    /// @return the button
    private Node optionsButton(Path screenshot) {
        org.jackhuang.hmcl.ui.SVGContainer dots =
                new org.jackhuang.hmcl.ui.SVGContainer(SVG.MORE_VERT, 10);

        StackPane button = new StackPane(dots);
        button.getStyleClass().add("screenshot-options");
        button.setPickOnBounds(true);
        button.setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setMaxSize(BUTTON_SIZE, BUTTON_SIZE);

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
        if (!extension.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT)))
            name = name.substring(0, name.length() - extension.length()).strip();
        if (name.isEmpty())
            return "文件名不能为空";

        Path target = screenshot.resolveSibling(name + extension);
        if (target.equals(screenshot))
            return null;

        try {
            if (Files.exists(target))
                return "已存在同名文件：" + target.getFileName();

            Files.move(screenshot, target);
            selection.remove(screenshot);
            reload();
            toast("已重命名为「" + target.getFileName() + "」");
            return null;
        } catch (Throwable e) {
            return "重命名失败：" + e.getMessage();
        }
    }

    /// Deletes one capture after confirmation.
    ///
    /// @param screenshot the image file
    private void delete(Path screenshot) {
        Controllers.confirm("确定删除这张截图吗？", screenshot.getFileName().toString(),
                MessageDialogPane.MessageType.WARNING, () -> {
                    if (deleteFiles(List.of(screenshot)) > 0)
                        toast("已删除 1 张截图");
                    selection.remove(screenshot);
                    reload();
                }, () -> {
                });
    }

    /// Deletes every selected capture after one confirmation.
    ///
    /// <p>One dialog for the whole batch: confirming per file made cleaning up dozens of captures
    /// tedious, which is the case this feature exists for.</p>
    private void deleteSelection() {
        if (selection.isEmpty())
            return;

        List<Path> targets = new ArrayList<>(selection);
        String message = targets.size() == 1
                ? "确定删除选中的截图吗？"
                : "确定删除选中的 " + targets.size() + " 张截图吗？";

        Controllers.confirm(message, "删除截图", MessageDialogPane.MessageType.WARNING, () -> {
            int deleted = deleteFiles(targets);
            selection.clear();
            selectionAnchor = -1;
            reload();
            toast(deleted > 0 ? "已删除 " + deleted + " 张截图" : "没有文件被删除");
        }, () -> {
        });
    }

    /// Deletes files, reporting how many actually went away.
    ///
    /// @param targets the files to delete
    /// @return how many were removed
    private int deleteFiles(List<Path> targets) {
        int deleted = 0;
        for (Path target : targets) {
            try {
                if (Files.deleteIfExists(target))
                    deleted++;
            } catch (Throwable e) {
                toast("删除失败：" + target.getFileName() + "（" + e.getMessage() + "）");
            }
        }
        return deleted;
    }

    /// Opens the containing folder with the file selected.
    ///
    /// @param screenshot the image file
    private void reveal(Path screenshot) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + screenshot.toAbsolutePath()).start();
                return;
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", screenshot.toAbsolutePath().toString()).start();
                return;
            }
            FXUtils.openFolder(screenshot.getParent());
        } catch (Throwable e) {
            toast("无法定位文件：" + e);
        }
    }

    /// Opens the folder holding this instance's screenshots.
    private void openDirectory() {
        if (repository == null || version == null) {
            summary.setText("未选择实例");
            return;
        }

        Path target = ScreenshotLocator.screenshotDirectory(repository, version);
        if (target == null) {
            summary.setText("没有找到截图目录");
            return;
        }

        try {
            Files.createDirectories(target);
            FXUtils.openFolder(target);
        } catch (Throwable e) {
            toast("无法打开目录：" + e);
        }
    }

    /// Shows a short message through the launcher's toast.
    ///
    /// @param message the text
    private static void toast(String message) {
        try {
            Controllers.showToast(message);
        } catch (Throwable ignored) {
            // Toast support is optional.
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

        org.jackhuang.hmcl.ui.SVGContainer iconBox =
                new org.jackhuang.hmcl.ui.SVGContainer(icon, 14);
        iconBox.setMouseTransparent(true);

        HBox content = new HBox(6, iconBox, label);
        content.setAlignment(Pos.CENTER);
        content.setMouseTransparent(true);

        StackPane surface = new StackPane(content);
        surface.getStyleClass().add("screenshot-button-surface");

        org.jackhuang.hmcl.ui.construct.RipplerContainer rippler =
                new org.jackhuang.hmcl.ui.construct.RipplerContainer(surface);
        rippler.getStyleClass().add("screenshot-button");

        rippler.setOnMouseClicked(event -> action.run());
        return rippler;
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
