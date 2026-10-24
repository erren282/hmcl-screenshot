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
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The screenshot browser shown as a tab inside the instance page.
///
/// <p>Built from the launcher's own components — [ComponentList] sections and [LineButton] rows —
/// so it matches the surrounding instance pages instead of looking like a foreign window. Each
/// capture is a row with a preview thumbnail, the file name and its size; clicking a row opens the
/// original in the system image viewer.</p>
///
/// <p>Implements the launcher's `GameInstanceLoadable` hook, so the page reloads by itself when the
/// user switches instances.</p>
final class ScreenshotPage extends VBox
        implements org.jackhuang.hmcl.ui.versions.VersionPage.GameInstanceLoadable {

    /// Thumbnail edge length used inside a row preview.
    private static final double THUMBNAIL_SIZE = 44;

    /// Larger preview edge length in the page header.
    private static final double PREVIEW_SIZE = 168;

    private final ComponentList overview = new ComponentList();
    private final ComponentList captures = new ComponentList();
    private final Label summary = new Label();
    private final Label emptyHint = new Label();
    private final ImageView preview = new ImageView();

    /// Every screenshot found for the instance.
    private final List<Path> screenshots = new ArrayList<>();

    private HMCLGameRepository repository;
    private String version;

    ScreenshotPage() {
        getStyleClass().add("screenshot-page");
        setSpacing(12);
        setPadding(new Insets(12, 16, 16, 16));

        getChildren().addAll(buildHeader(), buildScrollArea());
    }

    // ---------------------------------------------------------------- launcher hook

    @Override
    public void loadInstance(HMCLGameRepository repository, String version) {
        this.repository = repository;
        this.version = version;
        reload();
    }

    // -------------------------------------------------------------------- layout

    /// Builds the page header: title, summary and an actions row.
    ///
    /// @return the header node
    private Node buildHeader() {
        Label title = new Label("截图");
        title.getStyleClass().add("screenshot-title");

        summary.getStyleClass().add("screenshot-summary");

        VBox texts = new VBox(4, title, summary);

        LineButton refresh = LineButton.createNavigationButton();
        refresh.setTitle("刷新");
        refresh.setLeading(SVG.REFRESH);
        refresh.setOnAction(event -> reload());

        LineButton openFolder = LineButton.createNavigationButton();
        openFolder.setTitle("打开截图目录");
        openFolder.setLeading(SVG.FOLDER_OPEN);
        openFolder.setOnAction(event -> openDirectory());

        HBox actions = new HBox(8, refresh, openFolder);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, texts, spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// Builds the scrollable body: a preview pane plus the capture list.
    ///
    /// @return the scroll pane
    private Node buildScrollArea() {
        preview.setFitWidth(PREVIEW_SIZE);
        preview.setFitHeight(PREVIEW_SIZE);
        preview.setPreserveRatio(true);
        preview.setSmooth(true);

        StackPane previewFrame = new StackPane(preview);
        previewFrame.getStyleClass().add("screenshot-preview");
        previewFrame.setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
        previewFrame.setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);
        previewFrame.setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);

        emptyHint.getStyleClass().add("screenshot-hint");
        emptyHint.setWrapText(true);

        VBox side = new VBox(8, previewFrame, emptyHint);
        side.setAlignment(Pos.TOP_LEFT);
        side.setPrefWidth(PREVIEW_SIZE);

        VBox content = new VBox(12, overview, captures);
        content.setFillWidth(true);
        content.setPadding(new Insets(0, 4, 8, 0));

        ScrollPane scroll = new ScrollPane(content);
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
        captures.getContent().clear();
        preview.setImage(null);

        if (repository == null || version == null || version.isBlank()) {
            summary.setText("未选择实例");
            emptyHint.setText("打开一个实例后即可查看它的截图。");
            return;
        }

        List<Path> directories = ScreenshotLocator.candidateDirectories(repository, version);
        screenshots.addAll(ScreenshotLocator.collect(directories));

        summary.setText(screenshots.isEmpty()
                ? "「" + version + "」还没有截图"
                : "「" + version + "」共 " + screenshots.size() + " 张截图");

        if (screenshots.isEmpty()) {
            emptyHint.setText("截图为空。游戏内按 F2 截图后点「刷新」即可显示。");
            return;
        }

        emptyHint.setText("点击任意一行用系统看图器打开；这里显示的是最近一张。");
        showPreview(screenshots.get(0));

        for (Path screenshot : screenshots)
            captures.getContent().add(buildRow(screenshot));
    }

    /// Shows one capture in the header preview pane.
    ///
    /// @param screenshot the image file
    private void showPreview(Path screenshot) {
        try {
            preview.setImage(new Image(screenshot.toUri().toString(),
                    PREVIEW_SIZE, PREVIEW_SIZE, true, true, false));
        } catch (Throwable ignored) {
            // A broken image simply leaves the pane empty.
        }
    }

    // --------------------------------------------------------------------- rows

    /// Builds one capture row in the launcher's native line style.
    ///
    /// @param screenshot the image file
    /// @return the row
    private Node buildRow(Path screenshot) {
        LineButton row = LineButton.createNavigationButton();
        row.setTitle(screenshot.getFileName().toString());
        row.setSubtitle(formatSize(screenshot) + " · " + screenshot.getParent());
        row.setLeading(thumbnail(screenshot));

        row.setOnAction(event -> openImage(screenshot));
        row.setOnMouseEntered(event -> showPreview(screenshot));
        return row;
    }

    /// Builds a small preview image for a row icon.
    ///
    /// @param screenshot the image file
    /// @return the icon node
    private static Node thumbnail(Path screenshot) {
        try {
            Image image = new Image(screenshot.toUri().toString(),
                    THUMBNAIL_SIZE, THUMBNAIL_SIZE, true, true, false);
            ImageView view = new ImageView(image);
            view.setFitWidth(THUMBNAIL_SIZE - 8);
            view.setFitHeight(THUMBNAIL_SIZE - 8);
            view.setPreserveRatio(true);
            view.setSmooth(true);

            StackPane frame = new StackPane(view);
            frame.setPrefSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            frame.setMinSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            frame.setMaxSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            return frame;
        } catch (Throwable e) {
            return SVG.SCREENSHOT_MONITOR.createIcon();
        }
    }

    /// Formats a file size for the row subtitle.
    ///
    /// @param file the file
    /// @return the formatted size
    private static String formatSize(Path file) {
        try {
            long bytes = Files.size(file);
            if (bytes < 1024)
                return bytes + " B";
            if (bytes < 1024 * 1024)
                return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        } catch (IOException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ actions

    /// Opens one image in the system's default viewer.
    ///
    /// @param screenshot the image file
    private void openImage(Path screenshot) {
        showPreview(screenshot);
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(screenshot.toFile());
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Fall back to revealing the folder below.
        }

        try {
            FXUtils.openFolder(screenshot.getParent());
        } catch (Throwable ignored) {
            // Nothing else to try.
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

    /// Creates a flexible spacer.
    ///
    /// @return the spacer
    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
