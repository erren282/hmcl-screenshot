/*
 * 截图管理插件 —— 截图预览弹窗
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.screenshot;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.SVG;

import java.nio.file.Path;
import java.util.List;

/// A floating preview for one screenshot, shown as a launcher dialog.
///
/// <p>Walks through the whole set: the arrow keys, the on-screen arrows and the mouse wheel with a
/// modifier all move to the previous or next capture without closing the dialog, which is what
/// reviewing a folder of captures actually needs.</p>
///
/// <p>The plain wheel zooms and dragging pans. The zoom level is remembered for the session so
/// stepping through pictures does not reset the scale each time.</p>
final class ScreenshotPreview {

    /// Largest the picture may be drawn at zoom 1.
    private static final double MAX_FRAME_WIDTH = 640;
    private static final double MAX_FRAME_HEIGHT = 460;

    /// Corner button edge.
    private static final double BUTTON_SIZE = 22;

    /// Zoom limits and step per wheel notch.
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_STEP = 1.12;

    /// Zoom remembered across pictures in this session.
    private static double rememberedZoom = 1.0;

    private final ImageView view = new ImageView();
    private final Label zoomLabel = new Label();
    private final Label caption = new Label();
    private final Label position = new Label();

    /// The set being browsed and where this preview currently is.
    private final List<Path> files;
    private int index;

    /// Current zoom relative to the fitted size.
    private double zoom = 1.0;

    /// Size the picture is drawn at zoom 1, i.e. fitted into the frame.
    private double baseWidth;
    private double baseHeight;

    private double frameWidth;
    private double frameHeight;

    /// Drag state, in scene coordinates.
    private double dragAnchorX;
    private double dragAnchorY;
    private double startTranslateX;
    private double startTranslateY;

    /// Frame and clip, kept so switching pictures can resize them.
    private StackPane frame;
    private javafx.scene.shape.Rectangle clip;
    private StackPane root;

    /// Builds the preview for one picture of a set.
    ///
    /// @param files the whole set, newest first
    /// @param start the index to show first
    private ScreenshotPreview(List<Path> files, int start) {
        this.files = files;
        this.index = Math.max(0, Math.min(start, files.size() - 1));

        view.setPreserveRatio(true);
        view.setSmooth(true);
    }

    /// Builds the whole dialog content.
    ///
    /// @return the dialog layout
    private StackPane build() {
        frame = new StackPane(view);
        frame.getStyleClass().add("screenshot-preview-frame");

        clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        frame.setClip(clip);

        installDrag(frame);
        frame.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0)
                return;
            // Shift turns the wheel into navigation; without it the wheel keeps zooming, which is
            // the gesture users already learned here.
            if (event.isShiftDown() || event.isControlDown()) {
                step(event.getDeltaY() > 0 ? -1 : 1);
            } else {
                applyZoom(zoom * (event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP));
            }
            event.consume();
        });

        caption.getStyleClass().add("screenshot-preview-caption");
        zoomLabel.getStyleClass().add("screenshot-preview-zoom");
        position.getStyleClass().add("screenshot-preview-position");

        Label hint = new Label("← → 切换 · 滚轮缩放 · 拖拽移动");
        hint.getStyleClass().add("screenshot-preview-hint");

        HBox footer = new HBox(8, caption, spacer(), position, zoomLabel, hint);
        footer.setAlignment(Pos.CENTER_LEFT);

        // Navigation arrows float over the picture edges, hidden until hover like the close button.
        StackPane previous = navButton(SVG.ARROW_BACK, -1);
        StackPane next = navButton(SVG.ARROW_FORWARD, 1);
        StackPane.setAlignment(previous, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_RIGHT);
        StackPane.setMargin(previous, new Insets(0, 0, 0, 8));
        StackPane.setMargin(next, new Insets(0, 8, 0, 0));

        StackPane close = closeButton();
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(6, 6, 0, 0));

        StackPane frameHolder = new StackPane(frame, previous, next, close);

        close.setVisible(false);
        previous.setVisible(false);
        next.setVisible(false);
        frameHolder.setOnMouseEntered(event -> {
            close.setVisible(true);
            previous.setVisible(hasPrevious());
            next.setVisible(hasNext());
        });
        frameHolder.setOnMouseExited(event -> {
            close.setVisible(false);
            previous.setVisible(false);
            next.setVisible(false);
        });

        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(10, frameHolder, footer);
        card.getStyleClass().add("screenshot-preview-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        root = new StackPane(card);
        root.getStyleClass().add("screenshot-preview-dialog");
        root.setPadding(new Insets(0));
        root.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        // Arrow keys navigate, Escape closes: focus is needed for that, so the root takes it.
        root.setFocusTraversable(true);
        root.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case LEFT -> {
                    step(-1);
                    event.consume();
                }
                case RIGHT -> {
                    step(1);
                    event.consume();
                }
                case ESCAPE -> {
                    DialogUtils.close(root);
                    event.consume();
                }
                default -> {
                }
            }
        });
        javafx.application.Platform.runLater(root::requestFocus);

        showCurrent();
        return root;
    }

    /// Loads and displays the picture at [#index].
    private void showCurrent() {
        if (files.isEmpty())
            return;

        Path file = files.get(index);

        Image image = new Image(file.toUri().toString());
        view.setImage(image);

        double naturalWidth = image.isError() || image.getWidth() <= 0 ? MAX_FRAME_WIDTH : image.getWidth();
        double naturalHeight = image.isError() || image.getHeight() <= 0 ? MAX_FRAME_HEIGHT : image.getHeight();

        double fit = Math.min(MAX_FRAME_WIDTH / naturalWidth, MAX_FRAME_HEIGHT / naturalHeight);
        fit = Math.min(fit, 1.0);
        baseWidth = Math.max(1, naturalWidth * fit);
        baseHeight = Math.max(1, naturalHeight * fit);
        frameWidth = baseWidth;
        frameHeight = baseHeight;

        if (frame != null) {
            frame.setPrefSize(frameWidth, frameHeight);
            frame.setMinSize(frameWidth, frameHeight);
            frame.setMaxSize(frameWidth, frameHeight);
            clip.setWidth(frameWidth);
            clip.setHeight(frameHeight);
        }

        caption.setText(file.getFileName().toString());
        position.setText(files.size() > 1 ? (index + 1) + " / " + files.size() : "");

        // The remembered zoom is applied relative to the new picture's fitted size.
        applyZoom(rememberedZoom);
    }

    /// Moves to another picture in the set.
    ///
    /// @param delta -1 for the previous one, +1 for the next
    private void step(int delta) {
        int target = index + delta;
        if (target < 0 || target >= files.size())
            return;

        index = target;
        // Centre the new picture: keeping the previous pan would show a corner of it.
        view.setTranslateX(0);
        view.setTranslateY(0);
        showCurrent();
    }

    /// Whether a previous picture exists.
    private boolean hasPrevious() {
        return index > 0;
    }

    /// Whether a next picture exists.
    private boolean hasNext() {
        return index < files.size() - 1;
    }

    /// Builds a navigation arrow button.
    ///
    /// @param icon  the arrow
    /// @param delta the direction it moves
    /// @return the button
    private StackPane navButton(SVG icon, int delta) {
        org.jackhuang.hmcl.ui.SVGContainer glyph = new org.jackhuang.hmcl.ui.SVGContainer(icon, 14);

        StackPane button = new StackPane(glyph);
        button.getStyleClass().add("screenshot-preview-nav");
        button.setPickOnBounds(true);
        button.setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setMaxSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setOnMouseClicked(event -> {
            step(delta);
            event.consume();
        });
        return button;
    }

    /// Builds the close button.
    ///
    /// @return the button
    private StackPane closeButton() {
        org.jackhuang.hmcl.ui.SVGContainer icon =
                new org.jackhuang.hmcl.ui.SVGContainer(SVG.CLOSE, 10);

        StackPane button = new StackPane(icon);
        button.getStyleClass().add("screenshot-preview-close");
        button.setPickOnBounds(true);
        button.setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setMaxSize(BUTTON_SIZE, BUTTON_SIZE);
        button.setOnMouseClicked(event -> {
            if (root != null)
                DialogUtils.close(root);
            event.consume();
        });
        return button;
    }

    /// Makes the picture draggable inside its frame.
    ///
    /// @param frame the frame holding the picture
    private void installDrag(StackPane frame) {
        frame.setCursor(Cursor.OPEN_HAND);
        frame.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY)
                return;
            dragAnchorX = event.getSceneX();
            dragAnchorY = event.getSceneY();
            startTranslateX = view.getTranslateX();
            startTranslateY = view.getTranslateY();
            frame.setCursor(Cursor.CLOSED_HAND);
        });
        frame.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY)
                return;
            view.setTranslateX(startTranslateX + event.getSceneX() - dragAnchorX);
            view.setTranslateY(startTranslateY + event.getSceneY() - dragAnchorY);
        });
        frame.setOnMouseReleased(event -> frame.setCursor(Cursor.OPEN_HAND));
    }

    /// Applies a zoom factor, leaving the frame size untouched.
    ///
    /// @param value the requested zoom, clamped to the supported range
    private void applyZoom(double value) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        rememberedZoom = zoom;
        view.setFitWidth(baseWidth * zoom);
        view.setFitHeight(baseHeight * zoom);
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    /// Creates a flexible spacer.
    ///
    /// @return the spacer
    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /// Shows the preview for one picture of a set.
    ///
    /// @param files the whole set, newest first
    /// @param start the index to show
    static void show(List<Path> files, int start) {
        try {
            if (files == null || files.isEmpty())
                return;
            ScreenshotPreview preview = new ScreenshotPreview(files, start);
            Controllers.dialog(preview.build());
        } catch (Throwable ignored) {
            // A failed preview must not disturb the page.
        }
    }

    /// Shows a single picture, kept for callers that have no set.
    ///
    /// @param file the image being previewed
    static void show(Path file) {
        show(List.of(file), 0);
    }
}
