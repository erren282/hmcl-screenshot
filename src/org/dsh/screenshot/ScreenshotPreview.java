/*
 * 截图管理插件 —— 截图预览弹窗
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.screenshot;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;

import java.nio.file.Path;

/// A floating preview for one screenshot, shown as a launcher dialog.
///
/// <p>Deliberately not full-screen: it sizes itself to the image up to a comfortable maximum, so
/// the launcher stays visible behind it. The wheel zooms, and the dialog's own close button (or
/// Esc) dismisses it.</p>
final class ScreenshotPreview extends VBox {

    /// Largest the preview area may become, so the dialog never covers the whole window.
    private static final double MAX_PREVIEW_WIDTH = 720;
    private static final double MAX_PREVIEW_HEIGHT = 460;

    /// Zoom limits and step per wheel notch.
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.0;
    private static final double ZOOM_STEP = 1.12;

    private final ImageView view = new ImageView();
    private final Label caption = new Label();
    private final Label zoomLabel = new Label();

    private double zoom = 1.0;

    /// Builds the preview for one image.
    ///
    /// @param file the image file
    ScreenshotPreview(Path file) {
        getStyleClass().add("screenshot-preview-dialog");
        setSpacing(10);

        Image image = new Image(file.toUri().toString());
        view.setImage(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        // Fit the image inside the allowed box on open, keeping small images at their own size.
        double naturalWidth = image.isError() ? 0 : image.getWidth();
        double naturalHeight = image.isError() ? 0 : image.getHeight();
        double scale = 1.0;
        if (naturalWidth > MAX_PREVIEW_WIDTH)
            scale = Math.min(scale, MAX_PREVIEW_WIDTH / naturalWidth);
        if (naturalHeight > MAX_PREVIEW_HEIGHT)
            scale = Math.min(scale, MAX_PREVIEW_HEIGHT / naturalHeight);

        StackPane frame = new StackPane(view);
        frame.getStyleClass().add("screenshot-preview-frame");
        frame.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        frame.setAlignment(Pos.CENTER);

        applyZoom(scale);

        // The wheel is consumed so it zooms instead of scrolling anything behind the dialog.
        frame.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0)
                return;
            applyZoom(zoom * (event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP));
            event.consume();
        });

        caption.getStyleClass().add("screenshot-preview-caption");
        caption.setText(file.getFileName().toString());

        zoomLabel.getStyleClass().add("screenshot-preview-zoom");
        updateZoomLabel();

        Label hint = new Label("滚轮缩放");
        hint.getStyleClass().add("screenshot-preview-hint");

        javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(8, caption,
                spacer(), zoomLabel, hint);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(frame, footer);
        setPadding(new Insets(2, 2, 0, 2));
    }

    /// Applies a zoom factor to the image view.
    ///
    /// @param value the requested zoom, clamped to the supported range
    private void applyZoom(double value) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        view.setFitWidth(view.getImage() == null ? 0 : view.getImage().getWidth() * zoom);
        view.setFitHeight(0);
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
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        return spacer;
    }

    /// Shows this preview in a launcher dialog.
    ///
    /// @param file the image being previewed
    static void show(Path file) {
        try {
            Controllers.dialog(new ScreenshotPreview(file));
        } catch (Throwable ignored) {
            // A failed preview must not disturb the page.
        }
    }
}
