package io.github.bags156.rawdarkroom;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.bags156.rawdarkroom.core.AutoTune;
import io.github.bags156.rawdarkroom.core.LocalMap;
import io.github.bags156.rawdarkroom.core.Params;
import io.github.bags156.rawdarkroom.core.Pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole app. Photos arrive from the system picker or from another app's Share menu; each is
 * analysed and developed on the device and, by default, saved straight away. Sliders are there
 * for the photos that need a nudge, hidden until asked for. Nothing leaves the phone; the app has
 * no Internet permission.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_OPEN = 1;
    /** Longest side of the on-screen preview, in pixels. */
    private static final int PREVIEW_SIZE = 1600;
    private static final int JPEG_QUALITY = 95;
    private static final String OUTPUT_FOLDER = "RawDarkroom";

    private static final String[] EXIF_TAGS = {
        "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal",
        "Make", "Model", "LensModel", "FNumber", "ExposureTime", "ISOSpeedRatings",
        "PhotographicSensitivity", "FocalLength", "FocalLengthIn35mmFilm",
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
    };

    /** One opened photo and its edit. */
    private static final class Photo {
        final Uri uri;
        final String name;
        /** Null until the photo has been analysed. */
        volatile Params params;
        /** Regional brightness of the photo as currently rotated. Null until analysed. */
        volatile LocalMap map;
        /** Quarter turns clockwise, 0..3. */
        volatile int rotation;

        Photo(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private final List<Photo> photos = new ArrayList<>();
    private int index = -1;

    // Preview state. Written on the UI thread only.
    private int[] previewPixels;
    private int previewW;
    private int previewH;
    private Bitmap originalPreview;
    private Bitmap editedPreview;
    private boolean busy;
    private boolean updatingSliders;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Decoding, analysis and export, one job at a time. */
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    /** Preview rendering, kept separate so the screen stays live while photos are being saved. */
    private final ExecutorService renderWork = Executors.newSingleThreadExecutor();
    /** Pixel crunching, spread across the cores. */
    private final int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final ExecutorService pool = Executors.newFixedThreadPool(threads);
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final AtomicInteger renderGeneration = new AtomicInteger();
    /** Owned by the render thread. */
    private int[] renderBuffer;

    private static final String PREF_AUTO_SAVE = "auto_save";

    private ImageView image;
    private TextView status;
    private CheckBox autoSave;
    private View adjustPanel;
    private SharedPreferences prefs;
    private final List<Button> buttons = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();

    // ---------------------------------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setContentDescription("Photo preview. Touch and hold to see the original.");
        image.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && originalPreview != null) {
                image.setImageBitmap(originalPreview);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (editedPreview != null) {
                    image.setImageBitmap(editedPreview);
                }
            }
            return true;
        });
        root.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(Color.rgb(210, 210, 210));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(12), dp(8), dp(12), dp(4));
        status.setText("Tap Open, or share photos to this app from your gallery. "
                + "RAW (DNG) files from Master Mode work best.");
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "Open", v -> openPicker());
        addButton(row, "Prev", v -> show(index - 1));
        addButton(row, "Next", v -> show(index + 1));
        addButton(row, "Adjust", v -> toggleAdjust());
        addButton(row, "Rotate", v -> rotateCurrent());
        addButton(row, "Save", v -> exportCurrent());
        addButton(row, "Save all", v -> exportAll());
        HorizontalScrollView rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.addView(row);
        root.addView(rowScroll);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        autoSave = new CheckBox(this);
        autoSave.setText("Save automatically when photos are opened");
        autoSave.setTextColor(Color.rgb(210, 210, 210));
        autoSave.setChecked(prefs.getBoolean(PREF_AUTO_SAVE, true));
        autoSave.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(PREF_AUTO_SAVE, checked).apply());
        LinearLayout.LayoutParams autoSaveLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoSaveLayout.setMargins(dp(8), 0, dp(8), dp(4));
        root.addView(autoSave, autoSaveLayout);

        LinearLayout sliderColumn = new LinearLayout(this);
        sliderColumn.setOrientation(LinearLayout.VERTICAL);
        sliderColumn.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout adjustRow = new LinearLayout(this);
        adjustRow.setOrientation(LinearLayout.HORIZONTAL);
        addButton(adjustRow, "Auto", v -> autoTuneCurrent());
        addButton(adjustRow, "Reset", v -> resetCurrent());
        sliderColumn.addView(adjustRow);
        addSlider(sliderColumn, "Look", 0f, 1f, p -> p.look, (p, v) -> p.look = v);
        addSlider(sliderColumn, "Exposure", -3f, 3f, p -> p.exposure, (p, v) -> p.exposure = v);
        addSlider(sliderColumn, "Contrast", -1f, 1f, p -> p.contrast, (p, v) -> p.contrast = v);
        addSlider(sliderColumn, "Highlights", -1f, 1f, p -> p.highlights, (p, v) -> p.highlights = v);
        addSlider(sliderColumn, "Shadows", -1f, 1f, p -> p.shadows, (p, v) -> p.shadows = v);
        addSlider(sliderColumn, "Warmth", -1f, 1f, p -> p.warmth, (p, v) -> p.warmth = v);
        addSlider(sliderColumn, "Tint", -1f, 1f, p -> p.tint, (p, v) -> p.tint = v);
        addSlider(sliderColumn, "Saturation", -1f, 1f, p -> p.saturation, (p, v) -> p.saturation = v);
        ScrollView sliderScroll = new ScrollView(this);
        sliderScroll.addView(sliderColumn);
        sliderScroll.setVisibility(View.GONE);
        adjustPanel = sliderScroll;
        root.addView(sliderScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        setContentView(root);
        updateEnabled();
        if (savedInstanceState == null) {
            handleShare(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    /** Photos sent from another app's Share menu. */
    @SuppressWarnings("deprecation") // The typed getParcelableExtra overloads need API 33.
    private void handleShare(Intent intent) {
        if (intent == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                uris.add(u);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Uri u : list) {
                    if (u != null) {
                        uris.add(u);
                    }
                }
            }
        }
        loadPhotos(uris);
    }

    private void toggleAdjust() {
        adjustPanel.setVisibility(
                adjustPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        renderWork.shutdownNow();
        pool.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        b.setOnClickListener(onClick);
        row.addView(b);
        buttons.add(b);
    }

    private interface Getter {
        float get(Params p);
    }

    private interface Setter {
        void set(Params p, float value);
    }

    /** A labelled SeekBar bound to one field of the current photo's Params. */
    private final class Slider {
        static final int STEPS = 200;
        final String name;
        final float min;
        final float max;
        final Getter getter;
        final TextView label;
        final SeekBar bar;

        Slider(String name, float min, float max, Getter getter, Setter setter) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.getter = getter;
            label = new TextView(MainActivity.this);
            label.setTextColor(Color.rgb(190, 190, 190));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            bar = new SeekBar(MainActivity.this);
            bar.setMax(STEPS);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float value = Slider.this.min
                            + (Slider.this.max - Slider.this.min) * progress / (float) STEPS;
                    showValue(value);
                    Photo photo = current();
                    if (updatingSliders || photo == null || photo.params == null) {
                        return;
                    }
                    setter.set(photo.params, value);
                    render();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            showValue(0f);
        }

        void showValue(float value) {
            label.setText(String.format(Locale.US, "%s  %+.2f", name, value));
        }

        void load(Params p) {
            float value = getter.get(p);
            bar.setProgress(Math.round((value - min) / (max - min) * STEPS));
            showValue(value);
        }
    }

    private void addSlider(LinearLayout column, String name, float min, float max,
            Getter getter, Setter setter) {
        Slider s = new Slider(name, min, max, getter, setter);
        column.addView(s.label);
        column.addView(s.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        sliders.add(s);
    }

    private void loadSliders(Params p) {
        updatingSliders = true;
        for (Slider s : sliders) {
            s.load(p);
        }
        updatingSliders = false;
    }

    private void updateEnabled() {
        boolean ready = !busy && current() != null && current().params != null;
        for (Button b : buttons) {
            String label = b.getText().toString();
            if (label.equals("Open")) {
                b.setEnabled(!busy);
            } else if (label.equals("Prev")) {
                b.setEnabled(!busy && index > 0);
            } else if (label.equals("Next")) {
                b.setEnabled(!busy && index >= 0 && index < photos.size() - 1);
            } else if (label.equals("Save all")) {
                b.setEnabled(!busy && !photos.isEmpty());
            } else {
                b.setEnabled(ready);
            }
        }
        for (Slider s : sliders) {
            s.bar.setEnabled(ready);
        }
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private Photo current() {
        return index >= 0 && index < photos.size() ? photos.get(index) : null;
    }

    private String position() {
        Photo p = current();
        return p == null ? "" : (index + 1) + " / " + photos.size() + "  ·  " + p.name;
    }

    // ------------------------------------------------------------------------------- Open / show

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "image/x-adobe-dng"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_OPEN);
        } catch (RuntimeException e) {
            setStatus("No file picker is available on this device.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) {
                    uris.add(u);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        loadPhotos(uris);
    }

    /** Replaces the current set of photos, shows the first and, if enabled, saves them all. */
    private void loadPhotos(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        if (busy) {
            setStatus("Still saving the previous photos. Try again when that finishes.");
            return;
        }
        photos.clear();
        for (Uri u : uris) {
            photos.add(new Photo(u, displayName(u)));
        }
        show(0);
        if (autoSave.isChecked()) {
            exportAll();
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Fall through to a generic name.
        }
        return name == null || name.isEmpty() ? "photo" : name;
    }

    /** Loads photo {@code i}: decode a preview, auto-tune it if it is new, and show it. */
    private void show(int i) {
        if (busy || i < 0 || i >= photos.size()) {
            return;
        }
        index = i;
        final Photo photo = photos.get(i);
        final int generation = loadGeneration.incrementAndGet();
        renderGeneration.incrementAndGet();
        previewPixels = null;
        setStatus(position() + "  ·  opening…");
        updateEnabled();

        work.execute(() -> {
            try {
                Bitmap bmp = decode(photo.uri, PREVIEW_SIZE, photo.rotation);
                final int w = bmp.getWidth();
                final int h = bmp.getHeight();
                final int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                final Params tuned = photo.params != null
                        ? null : AutoTune.analyze(px, w, h, Params.DEFAULT_LOOK);
                final LocalMap map = photo.map != null ? photo.map : LocalMap.build(px, w, h);
                ui.post(() -> {
                    if (photo.params == null) {
                        photo.params = tuned;
                    }
                    if (photo.map == null) {
                        photo.map = map;
                    }
                    if (generation != loadGeneration.get()) {
                        return;
                    }
                    previewPixels = px;
                    previewW = w;
                    previewH = h;
                    originalPreview = bmp;
                    editedPreview = null;
                    image.setImageBitmap(bmp);
                    loadSliders(photo.params);
                    if (!busy) {
                        setStatus(position());
                    }
                    updateEnabled();
                    render();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                ui.post(() -> {
                    if (generation != loadGeneration.get()) {
                        return;
                    }
                    image.setImageDrawable(null);
                    originalPreview = null;
                    editedPreview = null;
                    setStatus(position() + "  ·  could not open: " + describe(e));
                    updateEnabled();
                });
            }
        });
    }

    private static String describe(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            return "not enough memory";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /**
     * Decodes with Android's own decoder, which reads DNG as well as JPEG, PNG, HEIF and WebP and
     * returns a neutral sRGB rendering.
     *
     * @param maxSide longest side in pixels, or 0 for full resolution
     */
    private Bitmap decode(Uri uri, int maxSide, int rotation) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap bmp = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            Size size = info.getSize();
            int longest = Math.max(size.getWidth(), size.getHeight());
            if (maxSide > 0 && longest > maxSide) {
                float k = maxSide / (float) longest;
                decoder.setTargetSize(
                        Math.max(1, Math.round(size.getWidth() * k)),
                        Math.max(1, Math.round(size.getHeight() * k)));
            }
        });
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = bmp.copy(Bitmap.Config.ARGB_8888, true);
            bmp.recycle();
            if (converted == null) {
                throw new IOException("unsupported pixel format");
            }
            bmp = converted;
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(90f * rotation);
            Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false);
            if (turned != bmp) {
                bmp.recycle();
            }
            if (!turned.isMutable()) {
                Bitmap mutable = turned.copy(Bitmap.Config.ARGB_8888, true);
                turned.recycle();
                turned = mutable;
            }
            bmp = turned;
        }
        return bmp;
    }

    // ----------------------------------------------------------------------------------- Editing

    private void autoTuneCurrent() {
        Photo photo = current();
        if (photo == null || previewPixels == null) {
            return;
        }
        float look = photo.params != null ? photo.params.look : Params.DEFAULT_LOOK;
        photo.params = AutoTune.analyze(previewPixels, previewW, previewH, look);
        loadSliders(photo.params);
        render();
    }

    private void resetCurrent() {
        Photo photo = current();
        if (photo == null || photo.params == null) {
            return;
        }
        photo.params = new Params();
        photo.params.look = 0f;
        loadSliders(photo.params);
        render();
    }

    private void rotateCurrent() {
        Photo photo = current();
        if (photo == null) {
            return;
        }
        photo.rotation = (photo.rotation + 1) % 4;
        photo.map = null;
        show(index);
    }

    /** Re-renders the preview with the current sliders. Only the newest request is drawn. */
    private void render() {
        final Photo photo = current();
        final int[] src = previewPixels;
        if (photo == null || photo.params == null || src == null) {
            return;
        }
        final Params params = photo.params.copy();
        final LocalMap map = photo.map;
        final int w = previewW;
        final int h = previewH;
        final int generation = renderGeneration.incrementAndGet();
        renderWork.execute(() -> {
            if (generation != renderGeneration.get()) {
                return;
            }
            try {
                if (renderBuffer == null || renderBuffer.length != src.length) {
                    renderBuffer = new int[src.length];
                }
                processParallel(new Pipeline(params), src, renderBuffer, w, h, map);
                final Bitmap out = Bitmap.createBitmap(renderBuffer, w, h, Bitmap.Config.ARGB_8888);
                ui.post(() -> {
                    if (generation == renderGeneration.get()) {
                        editedPreview = out;
                        image.setImageBitmap(out);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | OutOfMemoryError e) {
                ui.post(() -> setStatus(position() + "  ·  preview failed: " + describe(e)));
            }
        });
    }

    private void processParallel(Pipeline pipeline, int[] in, int[] out, int w, int h, LocalMap map)
            throws InterruptedException {
        int rowsPerJob = Math.max(1, (h + threads - 1) / threads);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int y = 0; y < h; y += rowsPerJob) {
            final int from = y;
            final int rows = Math.min(rowsPerJob, h - y);
            jobs.add(() -> {
                pipeline.processRows(in, out, from * w, w, rows, from, h, map);
                return null;
            });
        }
        pool.invokeAll(jobs);
    }

    // ------------------------------------------------------------------------------------ Export

    private void exportCurrent() {
        Photo photo = current();
        if (photo != null) {
            List<Photo> one = new ArrayList<>();
            one.add(photo);
            export(one);
        }
    }

    private void exportAll() {
        export(new ArrayList<>(photos));
    }

    private void export(final List<Photo> list) {
        if (busy || list.isEmpty()) {
            return;
        }
        busy = true;
        updateEnabled();
        work.execute(() -> {
            int saved = 0;
            String lastError = null;
            for (int i = 0; i < list.size(); i++) {
                final Photo photo = list.get(i);
                final String progress = "Saving " + (i + 1) + " of " + list.size() + ": " + photo.name;
                ui.post(() -> setStatus(progress));
                try {
                    exportOne(photo);
                    saved++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastError = "interrupted";
                    break;
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    lastError = photo.name + ": " + describe(e);
                }
            }
            final String summary = "Saved " + saved + " of " + list.size()
                    + " to Pictures/" + OUTPUT_FOLDER
                    + (lastError == null ? "" : "  ·  failed: " + lastError);
            ui.post(() -> {
                busy = false;
                setStatus(summary);
                updateEnabled();
            });
        });
    }

    /** Runs on the work thread. */
    private void exportOne(Photo photo) throws IOException, InterruptedException {
        final int rotation = photo.rotation;
        Params params = photo.params;
        LocalMap map = photo.map;
        if (params == null || map == null) {
            // Not viewed yet: analyse a preview-sized copy, exactly as the screen would have.
            Bitmap small = decode(photo.uri, PREVIEW_SIZE, rotation);
            int w = small.getWidth();
            int h = small.getHeight();
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            small.recycle();
            if (params == null) {
                params = AutoTune.analyze(px, w, h, Params.DEFAULT_LOOK);
            }
            if (map == null) {
                map = LocalMap.build(px, w, h);
            }
            final Params tuned = params;
            final LocalMap built = map;
            ui.post(() -> {
                if (photo.params == null) {
                    photo.params = tuned;
                }
                if (photo.map == null && photo.rotation == rotation) {
                    photo.map = built;
                }
            });
        }

        final Bitmap full = decode(photo.uri, 0, rotation);
        try {
            processBitmap(full, new Pipeline(params.copy()), map);
            save(full, photo);
        } finally {
            full.recycle();
        }
    }

    /** Processes a bitmap in place, in horizontal bands, to keep extra memory small. */
    private void processBitmap(final Bitmap bmp, final Pipeline pipeline, final LocalMap map)
            throws InterruptedException {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        final int bandRows = Math.max(1, Math.min(h, (1 << 20) / Math.max(1, w)));
        final AtomicInteger nextRow = new AtomicInteger();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            jobs.add(() -> {
                int[] band = new int[w * bandRows];
                while (true) {
                    int y = nextRow.getAndAdd(bandRows);
                    if (y >= h) {
                        return null;
                    }
                    int rows = Math.min(bandRows, h - y);
                    synchronized (bmp) {
                        bmp.getPixels(band, 0, w, 0, y, w, rows);
                    }
                    pipeline.processRows(band, band, 0, w, rows, y, h, map);
                    synchronized (bmp) {
                        bmp.setPixels(band, 0, w, 0, y, w, rows);
                    }
                }
            });
        }
        for (java.util.concurrent.Future<Void> f : pool.invokeAll(jobs)) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof OutOfMemoryError) {
                    throw (OutOfMemoryError) cause;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    private void save(Bitmap bmp, Photo photo) throws IOException {
        ContentResolver resolver = getContentResolver();
        String base = photo.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, base + "_RD.jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + OUTPUT_FOLDER);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri target = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (target == null) {
            throw new IOException("could not create the output file");
        }
        boolean ok = false;
        try {
            try (OutputStream os = resolver.openOutputStream(target)) {
                if (os == null || !bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) {
                    throw new IOException("could not write the JPEG");
                }
            }
            copyExif(resolver, photo.uri, target);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(target, done, null, null);
            ok = true;
        } finally {
            if (!ok) {
                resolver.delete(target, null, null);
            }
        }
    }

    /**
     * Carries capture details (time, camera, exposure, location) over to the export. Best effort:
     * a photo without readable metadata still saves.
     */
    private static void copyExif(ContentResolver resolver, Uri from, Uri to) {
        try (InputStream in = resolver.openInputStream(from);
                ParcelFileDescriptor pfd = resolver.openFileDescriptor(to, "rw")) {
            if (in == null || pfd == null) {
                return;
            }
            ExifInterface source = new ExifInterface(in);
            ExifInterface target = new ExifInterface(pfd.getFileDescriptor());
            for (String tag : EXIF_TAGS) {
                String value = source.getAttribute(tag);
                if (value != null) {
                    target.setAttribute(tag, value);
                }
            }
            // The pixels are already upright, so the export must not carry a rotation flag.
            target.setAttribute("Orientation", "1");
            target.setAttribute("Software", "Raw Darkroom");
            target.saveAttributes();
        } catch (IOException | RuntimeException ignored) {
            // Metadata is optional.
        }
    }
}
