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
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.ui.FXUtils;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The screenshot browser shown as a tab inside the instance page.
///
/// <p>Lists every capture found for the current instance as a thumbnail grid. Clicking a
/// thumbnail opens the full image in the system viewer, which keeps this page small while giving
/// access to the original file.</p>
final class ScreenshotPage extends VBox {

    /// Thumbnail edge length in pixels.
    private static final double THUMBNAIL_SIZE = 132;

    private final Label statusLabel = new Label();
    private final FlowPane grid = new FlowPane(12, 12);
    private final TextField searchField = new TextField();

    /// Every screenshot found for the instance.
    private final List<Path> screenshots = new ArrayList<>();

    /// The instance being displayed, or `null` before the page is bound.
    private HMCLGameRepository repository;
    private String version;

    ScreenshotPage() {
        getStyleClass().add("screenshot-page");
        setSpacing(10);
        setPadding(new Insets(12, 14, 14, 14));

        searchField.setPromptText("按文件名筛选");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> render());

        StackPane refresh = button("刷新", this::reload);
        StackPane openFolder = button("打开截图目录", this::openDirectory);

        HBox toolbar = new HBox(10, searchField, refresh, openFolder, spacer());
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("screenshot-toolbar");

        statusLabel.getStyleClass().add("screenshot-status");

        grid.setAlignment(Pos.TOP_LEFT);
        grid.getStyleClass().add("screenshot-grid");

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("screenshot-scroll");
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(toolbar, statusLabel, scroll);
        reload();
    }

    /// Points the page at an instance and reloads its screenshots.
    ///
    /// @param repository the instance's repository, or `null`
    /// @param version    the instance name, or `null`
    void bind(HMCLGameRepository repository, String version) {
        this.repository = repository;
        this.version = version;
        reload();
    }

    /// Returns the version this page currently shows.
    ///
    /// @return the version name, or `null`
    String version() {
        return version;
    }

    // -------------------------------------------------------------------- data

    /// Re-reads the screenshots from disk.
    void reload() {
        screenshots.clear();

        if (repository == null || version == null || version.isBlank()) {
            statusLabel.setText("未选择实例");
            grid.getChildren().clear();
            return;
        }

        List<Path> directories = ScreenshotLocator.candidateDirectories(repository, version);
        screenshots.addAll(ScreenshotLocator.collect(directories));

        if (screenshots.isEmpty()) {
            statusLabel.setText("「" + version + "」还没有截图");
        } else {
            statusLabel.setText("共 " + screenshots.size() + " 张截图");
        }
        render();
    }

    /// Rebuilds the thumbnail grid from the current filter.
    private void render() {
        grid.getChildren().clear();

        String query = searchField.getText() == null
                ? ""
                : searchField.getText().strip().toLowerCase(Locale.ROOT);

        for (Path screenshot : screenshots) {
            String name = screenshot.getFileName().toString();
            if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query))
                continue;
            try {
                grid.getChildren().add(buildThumbnail(screenshot));
            } catch (Throwable ignored) {
                // A broken image must not stop the grid from rendering.
            }
        }

        if (grid.getChildren().isEmpty() && !screenshots.isEmpty())
            statusLabel.setText("没有匹配的截图");
        else if (!screenshots.isEmpty())
            statusLabel.setText("共 " + screenshots.size() + " 张截图");
    }

    // ----------------------------------------------------------------- widgets

    /// Builds one thumbnail tile.
    ///
    /// @param screenshot the image file
    /// @return the tile
    private Node buildThumbnail(Path screenshot) {
        // Decoded at thumbnail size directly: a full-size capture would allocate far more memory
        // than a grid of tiles needs.
        Image image = new Image(screenshot.toUri().toString(),
                THUMBNAIL_SIZE, THUMBNAIL_SIZE, true, true, false);

        ImageView view = new ImageView(image);
        view.setFitWidth(THUMBNAIL_SIZE);
        view.setFitHeight(THUMBNAIL_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        StackPane frame = new StackPane(view);
        frame.getStyleClass().add("screenshot-thumb");
        frame.setPrefSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        frame.setMinSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        frame.setMaxSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE);

        Label caption = new Label(screenshot.getFileName().toString());
        caption.getStyleClass().add("screenshot-caption");
        caption.setMaxWidth(THUMBNAIL_SIZE);

        VBox tile = new VBox(6, frame, caption);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().add("screenshot-tile");
        FXUtils.installFastTooltip(tile, "点击用系统看图器打开\n" + screenshot);
        tile.setOnMouseClicked(event -> openImage(screenshot));
        return tile;
    }

    /// Opens one image in the system's default viewer.
    ///
    /// @param screenshot the image file
    private void openImage(Path screenshot) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(screenshot.toFile());
                statusLabel.setText("已打开 " + screenshot.getFileName());
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Fall back to revealing the folder below.
        }

        try {
            FXUtils.openFolder(screenshot.getParent());
            statusLabel.setText("无法直接打开图片，已定位到所在文件夹");
        } catch (Throwable e) {
            statusLabel.setText("打开失败：" + e);
        }
    }

    /// Opens the folder that holds this instance's screenshots.
    private void openDirectory() {
        if (repository == null || version == null) {
            statusLabel.setText("未选择实例");
            return;
        }

        List<Path> directories = ScreenshotLocator.candidateDirectories(repository, version);
        Path target = ScreenshotLocator.preferredDirectory(directories);
        if (target == null) {
            statusLabel.setText("没有找到截图目录");
            return;
        }

        try {
            Files.createDirectories(target);
            FXUtils.openFolder(target);
            statusLabel.setText("已打开 " + target);
        } catch (Throwable e) {
            statusLabel.setText("无法打开目录：" + e);
        }
    }

    /// Builds a flat button in the launcher's palette.
    ///
    /// @param text   the label
    /// @param action the click action
    /// @return the button
    private static StackPane button(String text, Runnable action) {
        Label label = new Label(text);
        label.getStyleClass().add("screenshot-button-text");

        StackPane button = new StackPane(label);
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
