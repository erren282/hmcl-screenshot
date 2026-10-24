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

/// A floating preview for one screenshot, shown as a launcher dialog.
///
/// <p>Sizes itself to the image up to a comfortable maximum so the launcher stays visible behind
/// it. The wheel zooms the picture while the frame keeps its size, and the picture can be dragged
/// inside that frame.</p>
final class ScreenshotPreview {

    /// Fixed frame size: it never changes, so zooming only affects the picture inside it.
    private static final double FRAME_WIDTH = 680;
    private static final double FRAME_HEIGHT = 440;

    /// Zoom limits and step per wheel notch.
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_STEP = 1.12;

    private final ImageView view = new ImageView();
    private final Label zoomLabel = new Label();

    /// Current zoom relative to the fitted size.
    private double zoom = 1.0;

    /// Size the picture is drawn at zoom 1, i.e. fitted into the frame.
    private double baseWidth;
    private double baseHeight;

    /// Drag state, in scene coordinates.
    private double dragAnchorX;
    private double dragAnchorY;
    private double startTranslateX;
    private double startTranslateY;

    /// Builds the preview for one image.
    ///
    /// @param file the image file
    private ScreenshotPreview(Path file) {
        Image image = new Image(file.toUri().toString());
        view.setImage(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        double naturalWidth = image.isError() || image.getWidth() <= 0 ? FRAME_WIDTH : image.getWidth();
        double naturalHeight = image.isError() || image.getHeight() <= 0 ? FRAME_HEIGHT : image.getHeight();

        // Base size fits the picture inside the frame; zoom scales from there.
        double fit = Math.min(FRAME_WIDTH / naturalWidth, FRAME_HEIGHT / naturalHeight);
        baseWidth = naturalWidth * fit;
        baseHeight = naturalHeight * fit;

        applyZoom(1.0);
    }

    /// Builds the whole dialog content, including the frame and footer.
    ///
    /// @param file the image file
    /// @return the dialog layout
    private StackPane build(Path file) {
        StackPane frame = new StackPane(view);
        frame.getStyleClass().add("screenshot-preview-frame");
        frame.setPrefSize(FRAME_WIDTH, FRAME_HEIGHT);
        frame.setMinSize(FRAME_WIDTH, FRAME_HEIGHT);
        frame.setMaxSize(FRAME_WIDTH, FRAME_HEIGHT);

        // Clip so a zoomed picture never spills outside the frame.
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(FRAME_WIDTH, FRAME_HEIGHT);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        frame.setClip(clip);

        installDrag(frame);
        frame.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() != 0) {
                applyZoom(zoom * (event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP));
                event.consume();
            }
        });

        Label caption = new Label(file.getFileName().toString());
        caption.getStyleClass().add("screenshot-preview-caption");

        zoomLabel.getStyleClass().add("screenshot-preview-zoom");
        updateZoomLabel();

        Label hint = new Label("滚轮缩放 · 拖拽移动");
        hint.getStyleClass().add("screenshot-preview-hint");

        HBox footer = new HBox(8, caption, spacer(), zoomLabel, hint);
        footer.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.VBox body = new javafx.scene.layout.VBox(10, frame, footer);
        body.setPadding(new Insets(2, 0, 0, 0));

        // A real dialog layout gives the launcher's own chrome, including its close handling.
        com.jfoenix.controls.JFXDialogLayout layout = new com.jfoenix.controls.JFXDialogLayout();
        layout.getStyleClass().add("screenshot-preview-dialog");
        layout.setBody(body);

        StackPane close = closeButton(layout);
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(10, 12, 0, 0));

        // The close button floats in the dialog's top-right corner, over the picture frame.
        StackPane wrapper = new StackPane(layout, close);
        wrapper.setPickOnBounds(false);
        StackPane.setAlignment(layout, Pos.CENTER);
        return wrapper;
    }

    /// Builds the close button shown in the dialog's top-right corner.
    ///
    /// @param layout the dialog layout to close
    /// @return the button
    private StackPane closeButton(StackPane layout) {
        StackPane button = new StackPane(SVG.CLOSE.createIcon());
        button.getStyleClass().add("screenshot-preview-close");
        button.setOnMouseClicked(event -> {
            DialogUtils.close(layout);
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
            // Only the picture moves; the frame and dialog keep their place.
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
        view.setFitWidth(baseWidth * zoom);
        view.setFitHeight(baseHeight * zoom);
        updateZoomLabel();
    }

    /// Updates the percentage readout.
    private void updateZoomLabel() {
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

    /// Shows this preview in a launcher dialog.
    ///
    /// @param file the image being previewed
    static void show(Path file) {
        try {
            ScreenshotPreview preview = new ScreenshotPreview(file);
            Controllers.dialog(preview.build(file));
        } catch (Throwable ignored) {
            // A failed preview must not disturb the page.
        }
    }
}
