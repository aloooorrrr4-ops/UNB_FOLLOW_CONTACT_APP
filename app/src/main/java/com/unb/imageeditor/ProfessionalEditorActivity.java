package com.unb.imageeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ProfessionalEditorActivity extends Activity {

    private static final int PICK_IMAGE = 4101;
    private static final int CREATE_EXPORT = 4102;
    private static final int PICK_LAYER_IMAGE = 4103;

    private final int BG = Color.rgb(20, 22, 26);
    private final int PANEL = Color.rgb(28, 31, 36);
    private final int PANEL_2 = Color.rgb(35, 39, 45);
    private final int LINE = Color.rgb(55, 60, 68);
    private final int TEXT = Color.rgb(239, 242, 246);
    private final int MUTED = Color.rgb(159, 167, 178);
    private final int ACCENT = Color.rgb(61, 139, 255);

    private EditorCanvasView canvas;
    private final LocalEditorEngine localEngine = new LocalEditorEngine();
    private final LocalTextRegionDetector textRegionDetector = new LocalTextRegionDetector();
    private LocalOcrEngine ocrEngine;
    private final ExecutorService localExecutor = Executors.newSingleThreadExecutor();
    private String projectId;
    private byte[] sourceBytes;
    private String sourceName = "image.png";

    private TextView statusText;
    private TextView zoomText;
    private TextView projectText;
    private TextView toolTitle;
    private LinearLayout toolOptions;
    private LinearLayout layerList;
    private LinearLayout channelList;
    private LinearLayout pathList;
    private LinearLayout resourceList;
    private LinearLayout historyList;
    private Button activeToolButton;
    private String activeTool = "move";
    private boolean desktopLayout = false;
    private View phoneInspectorPanel;
    private ScrollView inspectorBodyScroll;
    private HorizontalScrollView phoneContextScroll;
    private boolean phoneInspectorExpanded = false;
    private float lastImageTapX = 0f;
    private float lastImageTapY = 0f;
    private int brushSize = 45;
    private int brushOpacity = 100;
    private int pointerOffsetDp = 92;
    private boolean pointerWorkEnabled = false;
    private int selectionThreshold = 15;
    private String dodgeBurnType = "dodge";
    private String eraseFillMode = "eraser";
    private boolean eraserTransparent = true;
    private boolean eraseFillMatchColor = true;
    private int eraseFillTolerance = 24;
    private String eraseFillTargetColor = "#ffffff";
    private boolean eraseTargetPickerInternalTransition = false;
    private String colorPickerReturnTool = null;
    private String colorPickerReturnLabel = null;
    private boolean selectedTextColorPickPending = false;
    private boolean selectedTextColorPickerInternalTransition = false;
    private String selectedTextDraftText = "";
    private String selectedTextDraftSize = "";
    private int brightnessValue = 0;
    private int contrastValue = 0;
    private int saturationValue = 0;
    private String foregroundColor = "#ffffff";
    private int textSize = 42;
    private int textWidthPercent = 100;
    private String textFontFamily = "sans-serif";
    private String textFontLabel = "عربي حديث";
    private int textWeight = 400;
    private String pendingLayerImageName = "صورة إضافية";
    private final List<RectF> detectedTextRegions = new ArrayList<>();
    private RectF selectedTextRegion;
    private String recognizedText = "";
    private int recognizedTextConfidence = 0;
    private float cloneSourceX = Float.NaN;
    private float cloneSourceY = Float.NaN;
    private float cropAspectRatio = 0f;
    private String gradientType = "linear";
    private String gradientBackgroundColor = "#000000";
    private byte[] pendingExportBytes;
    private String pendingExportName = "export.png";

    private final List<String> history = new ArrayList<>();
    private final List<String> recentColors = new ArrayList<>();
    private boolean busy = false;
    private String pendingHistoryAction = null;
    private final AtomicInteger adjustmentGeneration = new AtomicInteger();
    private String activeAdjustmentOperation = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(14, 16, 19));
        getWindow().setNavigationBarColor(Color.rgb(14, 16, 19));

        desktopLayout = isDesktopLayout();
        ocrEngine = new LocalOcrEngine(this);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Math.max(0, insets.getSystemWindowInsetTop());
            int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        root.addView(buildMenuBar());
        root.addView(divider());
        root.addView(buildQuickBar());
        root.addView(divider());

        View workspace = desktopLayout ? buildDesktopWorkspace() : buildPhoneWorkspace();
        root.addView(workspace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        root.addView(buildStatusBar());

        setContentView(root);
        showTool("move", "تحريك", null);
        addHistory("جاهز");
        refreshLayers();
        pingServer();
    }

    private View buildMenuBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.rgb(16, 18, 21));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(desktopLayout ? 6 : 2), dp(desktopLayout ? 4 : 2),
                dp(desktopLayout ? 6 : 2), dp(desktopLayout ? 4 : 2));
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        String[] menus = {"ملف", "تعديل", "تحديد", "عرض", "صورة", "طبقة", "ألوان", "أدوات", "فلاتر", "نوافذ"};
        for (String name : menus) {
            Button b = flatButton(name, desktopLayout ? 13 : 12);
            b.setOnClickListener(v -> showMenuGroup(name));
            row.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(desktopLayout ? 38 : 34)));
        }

        scroller.addView(row);
        return scroller;
    }

    private View buildQuickBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(PANEL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(desktopLayout ? 6 : 3), dp(desktopLayout ? 5 : 3),
                dp(desktopLayout ? 6 : 3), dp(desktopLayout ? 5 : 3));
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        row.addView(actionButton("فتح", v -> chooseImage()));
        row.addView(actionButton("+ صورة", v -> chooseLayerImage()));
        row.addView(actionButton("حفظ", v -> exportProject("png")));
        row.addView(actionButton("محلي", v -> showServerDialog()));
        row.addView(verticalDivider());
        row.addView(actionButton("↶ تراجع", v -> remoteHistory("undo")));
        row.addView(actionButton("↷ إعادة", v -> remoteHistory("redo")));
        row.addView(verticalDivider());
        row.addView(actionButton("↺ 90°", v -> applyRemote("rotate", json("degrees", -90))));
        row.addView(actionButton("↻ 90°", v -> applyRemote("rotate", json("degrees", 90))));
        row.addView(actionButton("↔ عكس", v -> applyRemote("flip_horizontal", new JSONObject())));
        row.addView(actionButton("↕ عكس", v -> applyRemote("flip_vertical", new JSONObject())));
        row.addView(verticalDivider());
        row.addView(actionButton("ملاءمة", v -> canvas.fitToView()));
        row.addView(actionButton("100%", v -> canvas.setZoom(1f)));

        scroller.addView(row);
        return scroller;
    }

    private View buildDesktopWorkspace() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(BG);

        row.addView(buildToolRail(), new LinearLayout.LayoutParams(dp(86),
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(dividerVertical());

        canvas = createCanvas();
        row.addView(canvas, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        row.addView(dividerVertical());
        row.addView(buildInspector(), new LinearLayout.LayoutParams(dp(310),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private View buildPhoneWorkspace() {
        LinearLayout root = column();

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);

        canvas = createCanvas();
        middle.addView(canvas, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        middle.addView(dividerVertical());
        middle.addView(buildToolRail(), new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(middle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        phoneInspectorPanel = buildPhoneContextPanel();
        root.addView(phoneInspectorPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));
        return root;
    }

    private EditorCanvasView createCanvas() {
        EditorCanvasView v = new EditorCanvasView(this);
        v.setListener(new EditorCanvasView.Listener() {
            @Override
            public void onViewportChanged(float zoom, float imageX, float imageY) {
                if (zoomText != null) zoomText.setText(Math.round(zoom * 100f) + "%");
                if (statusText != null) {
                    statusText.setText(activeToolLabel() + "  •  X " +
                            Math.round(imageX) + "  Y " + Math.round(imageY));
                }
            }

            @Override
            public void onTapImage(float imageX, float imageY) {
                lastImageTapX = imageX;
                lastImageTapY = imageY;
                if ("color_picker".equals(activeTool)) {
                    pickLocalColor(imageX, imageY);
                } else if ("text_detect".equals(activeTool)) {
                    selectDetectedTextRegion(imageX, imageY);
                } else if ("fill".equals(activeTool) && projectId != null) {
                    applyRemote("fill", jsonOf("color", foregroundColor));
                } else if ("fuzzy_select".equals(activeTool) && projectId != null) {
                    applyRemote("select_contiguous", jsonOf(
                            "x", imageX, "y", imageY, "threshold", 0.15));
                } else if ("color_select".equals(activeTool) && projectId != null) {
                    applyRemote("select_color", jsonOf(
                            "color", foregroundColor, "threshold", 0.15));
                } else if ("text".equals(activeTool) && projectId != null) {
                    showAddTextDialog(imageX, imageY);
                }
            }

            @Override
            public void onStrokeCompleted(float[] imagePoints) {
                handleCanvasStroke(imagePoints);
            }
        });
        v.setPointerOffsetDp(pointerOffsetDp);
        v.setPointerActionEnabled(pointerWorkEnabled);
        return v;
    }

    private View buildToolRail() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PANEL);

        LinearLayout rail = column();
        int railPad = desktopLayout ? 6 : 4;
        rail.setPadding(dp(railPad), dp(desktopLayout ? 7 : 5),
                dp(railPad), dp(desktopLayout ? 8 : 5));

        addTool(rail, "move", "تحريك");
        addTool(rail, "select", "تحديد");
        addTool(rail, "free_select", "لاسو");
        addTool(rail, "fuzzy_select", "سحري");
        addTool(rail, "color_select", "حسب لون");
        addTool(rail, "crop", "قص");
        addTool(rail, "transform", "تحويل");
        addTool(rail, "perspective", "منظور");
        addTool(rail, "brush", "فرشاة");
        addTool(rail, "pencil", "قلم");
        addTool(rail, "erase_fill", "ممحاة/تعبئة");
        addTool(rail, "gradient", "تدرج");
        addTool(rail, "text", "نص");
        addTool(rail, "clone", "استنساخ");
        addTool(rail, "heal", "ترميم");
        addTool(rail, "smudge", "تلطيخ");
        addTool(rail, "dodge_burn", "إضاءة");
        addTool(rail, "color_picker", "لون");
        addTool(rail, "brightness_adjust", "سطوع");
        addTool(rail, "contrast_adjust", "تباين");
        addTool(rail, "saturation_adjust", "تشبع");
        addTool(rail, "zoom", "تكبير");

        if (!desktopLayout) {
            TextView split = label("—", 10, MUTED, false);
            split.setGravity(Gravity.CENTER);
            rail.addView(split, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

            addRailPanelButton(rail, "خصائص", "properties");
            addRailPanelButton(rail, "طبقات", "layers");
            addRailPanelButton(rail, "قنوات", "channels");
            addRailPanelButton(rail, "مسارات", "paths");
            addRailPanelButton(rail, "موارد", "resources");
            addRailPanelButton(rail, "سجل", "history");
        }

        scroll.addView(rail);
        return scroll;
    }

    private void addRailPanelButton(LinearLayout rail, String title, String tab) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(9.5f);
        b.setTextColor(MUTED);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setBackground(rounded(Color.rgb(31, 34, 39), 9));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.bottomMargin = dp(4);
        rail.addView(b, lp);

        b.setOnClickListener(v -> {
            openInspectorTab(tab);
            if ("layers".equals(tab)) refreshLayers();
            else if ("channels".equals(tab)) refreshChannels();
            else if ("paths".equals(tab)) refreshPaths();
            else if ("resources".equals(tab)) loadResources("fonts");
            else if ("history".equals(tab)) addHistoryRowsOnly();
        });
    }

    private void addTool(LinearLayout rail, String id, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(desktopLayout ? 11 : 10);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(3), dp(2), dp(3));
        b.setBackground(rounded(PANEL_2, 9));
        b.setTag(id);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(desktopLayout ? 48 : 42));
        lp.bottomMargin = dp(desktopLayout ? 5 : 4);
        rail.addView(b, lp);

        b.setOnClickListener(v -> {
            if (activeToolButton != null) {
                activeToolButton.setBackground(rounded(PANEL_2, 9));
            }
            activeToolButton = b;
            b.setBackground(rounded(ACCENT, 9));
            activeTool = id;
            showTool(id, label, b);
        });

        if ("move".equals(id)) activeToolButton = b;
    }

    private View buildPhoneContextPanel() {
        LinearLayout panel = column();
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));

        phoneContextScroll = new HorizontalScrollView(this);
        phoneContextScroll.setHorizontalScrollBarEnabled(false);
        phoneContextScroll.setFillViewport(false);

        LinearLayout host = column();

        toolOptions = new LinearLayout(this);
        toolOptions.setOrientation(LinearLayout.HORIZONTAL);
        toolOptions.setGravity(Gravity.CENTER_VERTICAL);

        layerList = new LinearLayout(this);
        layerList.setOrientation(LinearLayout.HORIZONTAL);
        layerList.setGravity(Gravity.CENTER_VERTICAL);

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.HORIZONTAL);
        channelList.setGravity(Gravity.CENTER_VERTICAL);

        pathList = new LinearLayout(this);
        pathList.setOrientation(LinearLayout.HORIZONTAL);
        pathList.setGravity(Gravity.CENTER_VERTICAL);

        resourceList = new LinearLayout(this);
        resourceList.setOrientation(LinearLayout.HORIZONTAL);
        resourceList.setGravity(Gravity.CENTER_VERTICAL);

        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.HORIZONTAL);
        historyList.setGravity(Gravity.CENTER_VERTICAL);

        host.addView(toolOptions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));
        host.addView(layerList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));
        host.addView(channelList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));
        host.addView(pathList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));
        host.addView(resourceList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));
        host.addView(historyList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(94)));

        phoneContextScroll.addView(host);
        panel.addView(phoneContextScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        showInspector("properties");
        return panel;
    }

    private View buildInspector() {
        LinearLayout panel = column();
        panel.setBackgroundColor(PANEL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(desktopLayout ? 5 : 2), dp(desktopLayout ? 5 : 3),
                dp(desktopLayout ? 5 : 2), dp(2));

        Button props = tabButton("خصائص");
        Button layers = tabButton("طبقات");
        Button channels = tabButton("قنوات");
        Button paths = tabButton("مسارات");
        Button resources = tabButton("موارد");
        Button hist = tabButton("سجل");
        Button collapse = tabButton("⌄");

        int tabHeight = desktopLayout ? 40 : 36;
        Button[] mainTabs = {props, layers, channels, paths, resources, hist};

        if (desktopLayout) {
            HorizontalScrollView tabScroll = new HorizontalScrollView(this);
            tabScroll.setHorizontalScrollBarEnabled(false);
            for (Button tab : mainTabs) {
                tabs.addView(tab, new LinearLayout.LayoutParams(dp(72), dp(tabHeight)));
            }
            tabScroll.addView(tabs);
            panel.addView(tabScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(tabHeight + 8)));
        } else {
            for (Button tab : mainTabs) {
                tab.setTextSize(9.5f);
                tab.setPadding(dp(2), 0, dp(2), 0);
                tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(tabHeight), 1f));
            }
            collapse.setTextSize(12);
            collapse.setPadding(0, 0, 0, 0);
            tabs.addView(collapse, new LinearLayout.LayoutParams(dp(32), dp(tabHeight)));
            panel.addView(tabs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(tabHeight + 8)));
        }

        ScrollView bodyScroll = new ScrollView(this);
        inspectorBodyScroll = bodyScroll;
        LinearLayout body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(12));
        bodyScroll.addView(body);
        panel.addView(bodyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        toolOptions = column();
        layerList = column();
        channelList = column();
        pathList = column();
        resourceList = column();
        historyList = column();

        body.addView(toolOptions);
        body.addView(layerList);
        body.addView(channelList);
        body.addView(pathList);
        body.addView(resourceList);
        body.addView(historyList);

        props.setOnClickListener(v -> openInspectorTab("properties"));
        layers.setOnClickListener(v -> {
            openInspectorTab("layers");
            refreshLayers();
        });
        channels.setOnClickListener(v -> {
            openInspectorTab("channels");
            refreshChannels();
        });
        paths.setOnClickListener(v -> {
            openInspectorTab("paths");
            refreshPaths();
        });
        resources.setOnClickListener(v -> {
            openInspectorTab("resources");
            loadResources("fonts");
        });
        hist.setOnClickListener(v -> openInspectorTab("history"));
        collapse.setOnClickListener(v -> setPhoneInspectorExpanded(false));

        showInspector("properties");
        if (!desktopLayout) bodyScroll.setVisibility(View.GONE);
        return panel;
    }

    private void openInspectorTab(String tab) {
        showInspector(tab);
        if (!desktopLayout && phoneContextScroll != null) {
            phoneContextScroll.post(() -> phoneContextScroll.scrollTo(0, 0));
        }
    }

    private void setPhoneInspectorExpanded(boolean expanded) {
        if (desktopLayout || phoneInspectorPanel == null) return;
        phoneInspectorExpanded = expanded;

        ViewGroup.LayoutParams raw = phoneInspectorPanel.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.height = dp(expanded ? 188 : 48);
            phoneInspectorPanel.setLayoutParams(lp);
        }

        if (inspectorBodyScroll != null) {
            inspectorBodyScroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private void showInspector(String tab) {
        if (toolOptions == null) return;
        toolOptions.setVisibility("properties".equals(tab) ? View.VISIBLE : View.GONE);
        layerList.setVisibility("layers".equals(tab) ? View.VISIBLE : View.GONE);
        channelList.setVisibility("channels".equals(tab) ? View.VISIBLE : View.GONE);
        pathList.setVisibility("paths".equals(tab) ? View.VISIBLE : View.GONE);
        resourceList.setVisibility("resources".equals(tab) ? View.VISIBLE : View.GONE);
        historyList.setVisibility("history".equals(tab) ? View.VISIBLE : View.GONE);
    }

    private void showTool(String id, String label, Button source) {
        if ("erase_target".equals(colorPickerReturnTool)) {
            if ("color_picker".equals(id)) {
                if (eraseTargetPickerInternalTransition) {
                    eraseTargetPickerInternalTransition = false;
                } else {
                    colorPickerReturnTool = null;
                    colorPickerReturnLabel = null;
                }
            } else {
                colorPickerReturnTool = null;
                colorPickerReturnLabel = null;
                eraseTargetPickerInternalTransition = false;
            }
        }

        if (selectedTextColorPickPending) {
            if ("color_picker".equals(id)) {
                if (selectedTextColorPickerInternalTransition) {
                    // One-shot allowance for the temporary picker opened by
                    // the selected-text dialog itself.
                    selectedTextColorPickerInternalTransition = false;
                } else {
                    // The user explicitly selected the standalone picker.
                    // Do not let a stale text-edit draft hijack its next sample.
                    clearPendingSelectedTextColorPick();
                }
            } else {
                clearPendingSelectedTextColorPick();
            }
        }
        if (source != null && activeToolButton != source) {
            if (activeToolButton != null) activeToolButton.setBackground(rounded(PANEL_2, 9));
            activeToolButton = source;
            source.setBackground(rounded(ACCENT, 9));
        }

        activeTool = id;
        if (canvas != null) {
            String canvasMode;
            if ("erase_fill".equals(id)) canvasMode = eraseFillMode;
            else if ("transform".equals(id) || "perspective".equals(id)) canvasMode = "transform_quad";
            else if ("text".equals(id) && canvas.hasTextOverlay()) canvasMode = "text_place";
            else if ("image_layer".equals(id) && canvas.hasImageOverlay()) canvasMode = "image_place";
            else canvasMode = id;
            canvas.setInteractionMode(canvasMode);
            canvas.setPointerActionEnabled(pointerWorkEnabled);
            String previewColor = "erase_fill".equals(id) && eraseFillMatchColor
                    ? eraseFillTargetColor : foregroundColor;
            canvas.setStrokePreview(brushSize, parseColorSafe(previewColor));
        }
        if (toolOptions == null) return;

        showInspector("properties");
        if (!desktopLayout && phoneContextScroll != null) {
            phoneContextScroll.post(() -> phoneContextScroll.scrollTo(0, 0));
        }

        toolOptions.removeAllViews();
        toolTitle = label("أداة: " + label, desktopLayout ? 17 : 13, TEXT, true);
        toolTitle.setGravity(Gravity.CENTER_VERTICAL);
        if (!desktopLayout) {
            toolTitle.setPadding(dp(10), 0, dp(10), 0);
            toolTitle.setBackground(rounded(Color.rgb(31, 34, 39), 8));
            toolOptions.addView(toolTitle, new LinearLayout.LayoutParams(
                    dp(110), dp(82)));
        } else {
            toolOptions.addView(toolTitle);
        }

        if ("erase_fill".equals(id)) {
            toolOptions.addView(actionButton(
                    "eraser".equals(eraseFillMode) ? "ممحاة ✓" : "ممحاة",
                    v -> {
                        eraseFillMode = "eraser";
                        showTool("erase_fill", "ممحاة/تعبئة", null);
                        setStatus("وضع الممحاة");
                    }));
            toolOptions.addView(actionButton(
                    "fill".equals(eraseFillMode) ? "تعبئة ✓" : "تعبئة",
                    v -> {
                        eraseFillMode = "fill";
                        showTool("erase_fill", "ممحاة/تعبئة", null);
                        setStatus("وضع التعبئة");
                    }));

            toolOptions.addView(actionButton(
                    eraseFillMatchColor ? "حسب اللون ✓" : "حسب اللون",
                    v -> {
                        eraseFillMatchColor = true;
                        showTool("erase_fill", "ممحاة/تعبئة", null);
                        setStatus("سيتم التنفيذ على اللون المستهدف فقط");
                    }));
            toolOptions.addView(actionButton(
                    eraseFillMatchColor ? "عادي" : "عادي ✓",
                    v -> {
                        eraseFillMatchColor = false;
                        showTool("erase_fill", "ممحاة/تعبئة", null);
                        setStatus("الوضع العادي: التنفيذ على كل البكسلات تحت الأداة");
                    }));

            if (eraseFillMatchColor) {
                toolOptions.addView(actionButton(
                        "اللون المستهدف " + eraseFillTargetColor,
                        v -> showEraseFillTargetColorDialog()));
                toolOptions.addView(actionButton(
                        "التقاط المستهدف من الصورة",
                        v -> startEraseFillTargetColorPick()));
                addLiveSlider(toolOptions, "سماحية اللون", 0, 100,
                        eraseFillTolerance, value -> eraseFillTolerance = value);

                toolOptions.addView(actionButton(
                        localEngine.hasSelection()
                                ? "تطبيق على التحديد"
                                : "تطبيق على التحديد (حدد منطقة)",
                        v -> applyEraseFillToSelection()));
            }

            if ("eraser".equals(eraseFillMode)) {
                toolOptions.addView(actionButton(
                        eraserTransparent ? "مسح شفاف ✓" : "مسح شفاف",
                        v -> {
                            eraserTransparent = !eraserTransparent;
                            showTool("erase_fill", "ممحاة/تعبئة", null);
                        }));
                if (!eraserTransparent) {
                    toolOptions.addView(actionButton(
                            "لون الاستبدال " + foregroundColor,
                            v -> showColorPaletteDialog(
                                    "لون الاستبدال", "erase_fill", "ممحاة/تعبئة")));
                    toolOptions.addView(actionButton(
                            "التقاط لون الاستبدال",
                            v -> startColorPickFor("erase_fill", "ممحاة/تعبئة")));
                }
            } else {
                toolOptions.addView(actionButton(
                        "لون التعبئة " + foregroundColor,
                        v -> showColorPaletteDialog(
                                "لون التعبئة", "erase_fill", "ممحاة/تعبئة")));
                toolOptions.addView(actionButton(
                        "التقاط لون التعبئة",
                        v -> startColorPickFor("erase_fill", "ممحاة/تعبئة")));
            }

            addLiveSlider(toolOptions, "الحجم", 1, 300, brushSize, value -> {
                brushSize = value;
                if (canvas != null) {
                    String preview = eraseFillMatchColor
                            ? eraseFillTargetColor : foregroundColor;
                    canvas.setStrokePreview(brushSize, parseColorSafe(preview));
                }
            });
            addLiveSlider(toolOptions, "العتامة", 0, 100, brushOpacity,
                    value -> brushOpacity = value);
            addLiveSlider(toolOptions, "ارتفاع الرأس", 48, 160, pointerOffsetDp, value -> {
                pointerOffsetDp = value;
                if (canvas != null) canvas.setPointerOffsetDp(pointerOffsetDp);
            });

            addPointerModeControls(toolOptions);

            TextView eraseHelp = label(
                    eraseFillMatchColor
                            ? (localEngine.hasSelection()
                                ? ("الهدف " + eraseFillTargetColor +
                                   " • السماحية " + eraseFillTolerance +
                                   ("eraser".equals(eraseFillMode)
                                       ? (eraserTransparent
                                           ? " — التحديد ثابت. يزيل اللون المستهدف ويحاول ترميم الخلفية المحيطة."
                                           : " — التحديد ثابت. يستبدل اللون المستهدف بلون الاستبدال المختار.")
                                       : " — التحديد ثابت. يعبئ اللون المستهدف بلون التعبئة المختار."))
                                : ("الهدف " + eraseFillTargetColor +
                                   " • السماحية " + eraseFillTolerance +
                                   " — الأبيض وبقية الألوان لن تتأثر إذا كانت خارج السماحية."))
                            : "الوضع العادي يطبق الأداة على كل ما يمر تحته الرأس.",
                    11, MUTED, false);
            eraseHelp.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(eraseHelp,
                    new LinearLayout.LayoutParams(dp(310), dp(82)));
        } else if (Arrays.asList("brush", "pencil", "clone", "heal", "smudge", "dodge_burn").contains(id)) {
            addLiveSlider(toolOptions, "الحجم", 1, 300, brushSize, value -> {
                brushSize = value;
                if (canvas != null) {
                    canvas.setStrokePreview(brushSize, parseColorSafe(foregroundColor));
                }
            });
            addLiveSlider(toolOptions, "العتامة", 0, 100, brushOpacity,
                    value -> brushOpacity = value);
            addSlider(toolOptions, "الصلابة", 0, 100, 70, null);
            addSlider(toolOptions, "التباعد", 1, 200, 20, null);
            addLiveSlider(toolOptions, "ارتفاع الرأس", 48, 160, pointerOffsetDp, value -> {
                pointerOffsetDp = value;
                if (canvas != null) canvas.setPointerOffsetDp(pointerOffsetDp);
            });
            toolOptions.addView(actionButton("اللون " + foregroundColor, v ->
                    showColorPaletteDialog("اختيار اللون", id, label)));
            toolOptions.addView(actionButton("التعرف من الصورة", v ->
                    startColorPickFor(id, label)));
            addPointerModeControls(toolOptions);

            if ("clone".equals(id) || "heal".equals(id)) {
                toolOptions.addView(actionButton("خذ المصدر من المؤشر", v ->
                        setCloneSourceAtPointer()));
                toolOptions.addView(actionButton("إلغاء المصدر", v -> {
                    cloneSourceX = Float.NaN;
                    cloneSourceY = Float.NaN;
                    setStatus("تم مسح مصدر " + ("heal".equals(id) ? "الترميم" : "الاستنساخ"));
                }));
            }

            if ("dodge_burn".equals(id)) {
                toolOptions.addView(actionButton(
                        "dodge".equals(dodgeBurnType) ? "إضاءة ✓" : "إضاءة", v -> {
                            dodgeBurnType = "dodge";
                            showTool("dodge_burn", "إضاءة/حرق", null);
                        }));
                toolOptions.addView(actionButton(
                        "burn".equals(dodgeBurnType) ? "حرق ✓" : "حرق", v -> {
                            dodgeBurnType = "burn";
                            showTool("dodge_burn", "إضاءة/حرق", null);
                        }));
            }
        } else if ("text".equals(id)) {
            addLiveSlider(toolOptions, "حجم الخط", 6, 300, textSize, value -> {
                textSize = value;
                updateTextOverlayStyle();
            });
            addLiveSlider(toolOptions, "عرض الخط", 50, 200, textWidthPercent, value -> {
                textWidthPercent = value;
                updateTextOverlayStyle();
            });
            addLiveSlider(toolOptions, "سماكة الخط", 100, 900, textWeight, value -> {
                textWeight = value;
                updateTextOverlayStyle();
            });

            toolOptions.addView(actionButton("+ إضافة نص", v ->
                    showAddTextDialog(lastImageTapX, lastImageTapY)));
            toolOptions.addView(actionButton("✓ تثبيت النص", v -> commitTextOverlay()));
            toolOptions.addView(actionButton("✕ إلغاء النص", v -> {
                if (canvas != null) canvas.clearTextOverlay();
                setStatus("تم إلغاء النص غير المثبت");
            }));

            toolOptions.addView(actionButton("الخط: " + textFontLabel, v ->
                    showFontPickerDialog()));

            toolOptions.addView(actionButton("لون النص", v ->
                    showColorPaletteDialog("لون النص", "text", "نص")));
            toolOptions.addView(actionButton("التعرف على اللون", v ->
                    startColorPickFor("text", "نص")));

            toolOptions.addView(actionButton("اكتشاف النصوص", v -> detectTextRegions()));
            toolOptions.addView(actionButton("تحديد نص يدوي", v -> startManualTextSelection()));
            toolOptions.addView(actionButton("تحديد عند المؤشر", v -> selectTextAtPointer()));
            toolOptions.addView(actionButton("تعرف على المحدد", v -> recognizeSelectedText(false)));
            toolOptions.addView(actionButton("تعديل المحدد", v -> recognizeSelectedText(true)));
            toolOptions.addView(actionButton("قص المحدد", v -> cropSelectedTextRegion()));
            toolOptions.addView(actionButton("حذف المحدد", v -> deleteSelectedTextRegion()));

            toolOptions.addView(actionButton("كل الخطوط العربية", v -> {
                openInspectorTab("resources");
                loadResources("fonts");
            }));
            toolOptions.addView(actionButton("النقوش والزخارف", v -> {
                openInspectorTab("resources");
                loadResources("patterns");
            }));

            addPointerModeControls(toolOptions);

            TextView textState = label(
                    canvas != null && canvas.hasTextOverlay()
                            ? "النص غير مثبت: اسحب لتحريكه • المقبض السفلي للتكبير • العلوي للتدوير"
                            : selectedTextRegion == null
                                ? "إضافة نص: أنشئه ثم حرّكه وكبّره ودوّره قبل التثبيت."
                                : ("المحدد " + Math.round(selectedTextRegion.width()) + "×" +
                                   Math.round(selectedTextRegion.height()) +
                                   (recognizedText.isEmpty() ? "" :
                                           " • OCR " + recognizedTextConfidence + "%")),
                    11, MUTED, false);
            textState.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(textState, new LinearLayout.LayoutParams(dp(300), dp(82)));
        } else if ("image_layer".equals(id)) {
            toolOptions.addView(actionButton("✓ تثبيت الصورة كطبقة", v ->
                    commitImageOverlay()));
            toolOptions.addView(actionButton("✕ إلغاء الصورة", v -> {
                if (canvas != null) canvas.clearImageOverlay();
                pendingLayerImageName = "صورة إضافية";
                showTool("move", "تحريك", null);
                setStatus("تم إلغاء الصورة الإضافية");
            }));
            toolOptions.addView(actionButton("+ اختيار صورة أخرى", v ->
                    chooseLayerImage()));
            toolOptions.addView(actionButton("فتح لوحة الطبقات", v -> {
                openInspectorTab("layers");
                refreshLayers();
            }));

            TextView imageHelp = label(
                    canvas != null && canvas.hasImageOverlay()
                            ? "اسحب الصورة نفسها للتحريك • المقبض السفلي للتكبير/التصغير • المقبض العلوي للتدوير • ثم ثبّتها كطبقة."
                            : "اختر صورة إضافية لتظهر فوق الصورة الحالية كطبقة مستقلة.",
                    11, MUTED, false);
            imageHelp.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(imageHelp, new LinearLayout.LayoutParams(dp(330), dp(82)));
        } else if ("color_picker".equals(id)) {
            addLiveSlider(toolOptions, "ارتفاع الرأس", 48, 160, pointerOffsetDp, value -> {
                pointerOffsetDp = value;
                if (canvas != null) canvas.setPointerOffsetDp(pointerOffsetDp);
            });
            TextView colorInfo = label("اللون الحالي  " + foregroundColor,
                    desktopLayout ? 13 : 11, TEXT, true);
            colorInfo.setPadding(dp(10), 0, dp(10), 0);
            colorInfo.setBackground(rounded(parseColorSafe(foregroundColor), 8));
            colorInfo.setTextColor(colorLuminance(parseColorSafe(foregroundColor)) > 150
                    ? Color.BLACK : Color.WHITE);
            toolOptions.addView(colorInfo, new LinearLayout.LayoutParams(dp(160), dp(72)));
            toolOptions.addView(actionButton("التقاط اللون", v -> pickColorAtPointer()));
            addPointerModeControls(toolOptions);
            TextView help = label("حرّك المؤشر للمكان المطلوب ثم اضغط التقاط اللون.",
                    11, MUTED, false);
            help.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(help, new LinearLayout.LayoutParams(dp(240), dp(82)));
        } else if ("brightness_adjust".equals(id)) {
            addAdjustmentSlider(toolOptions, "السطوع", "brightness", brightnessValue,
                    value -> brightnessValue = value);
        } else if ("contrast_adjust".equals(id)) {
            addAdjustmentSlider(toolOptions, "التباين", "contrast", contrastValue,
                    value -> contrastValue = value);
        } else if ("saturation_adjust".equals(id)) {
            addAdjustmentSlider(toolOptions, "التشبع", "saturation", saturationValue,
                    value -> saturationValue = value);
        } else if ("crop".equals(id)) {
            cropAspectRatio = 0f;
            toolOptions.addView(actionButton("يدوي", v -> {
                cropAspectRatio = 0f;
                setStatus("القص اليدوي: اسحب المستطيل بالحجم الذي تريد");
            }));
            toolOptions.addView(actionButton("1:1", v -> {
                cropAspectRatio = 1f;
                setStatus("القص بنسبة 1:1");
            }));
            toolOptions.addView(actionButton("4:3", v -> {
                cropAspectRatio = 4f / 3f;
                setStatus("القص بنسبة 4:3");
            }));
            toolOptions.addView(actionButton("16:9", v -> {
                cropAspectRatio = 16f / 9f;
                setStatus("القص بنسبة 16:9");
            }));
            toolOptions.addView(actionButton("9:16", v -> {
                cropAspectRatio = 9f / 16f;
                setStatus("القص بنسبة 9:16");
            }));
            addLiveSlider(toolOptions, "ارتفاع الرأس", 48, 160, pointerOffsetDp, value -> {
                pointerOffsetDp = value;
                if (canvas != null) canvas.setPointerOffsetDp(pointerOffsetDp);
            });
            addPointerModeControls(toolOptions);
            TextView help = label("المس من أسفل، والقص يعمل عند رأس المؤشر فوق إصبعك",
                    11, MUTED, false);
            help.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(help, new LinearLayout.LayoutParams(dp(240), dp(82)));
        } else if ("fuzzy_select".equals(id) || "color_select".equals(id)) {
            addLiveSlider(toolOptions, "الحساسية", 1, 100, selectionThreshold, value ->
                    selectionThreshold = value);
            addLiveSlider(toolOptions, "ارتفاع الرأس", 48, 160, pointerOffsetDp, value -> {
                pointerOffsetDp = value;
                if (canvas != null) canvas.setPointerOffsetDp(pointerOffsetDp);
            });

            if ("color_select".equals(id)) {
                toolOptions.addView(actionButton("لون " + foregroundColor, v ->
                        showColorPaletteDialog("لون التحديد", "color_select", "حسب لون")));
                toolOptions.addView(actionButton("التعرف من الصورة", v ->
                        startColorPickFor("color_select", "حسب لون")));
            }

            addPointerModeControls(toolOptions);

            TextView help = label(
                    "fuzzy_select".equals(id)
                            ? "حرّك المؤشر إلى المنطقة ثم استخدم تحريك + عمل."
                            : "يحدد كل البكسلات القريبة من اللون الحالي.",
                    11, MUTED, false);
            help.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(help, new LinearLayout.LayoutParams(dp(250), dp(82)));
        } else if ("select".equals(id)) {
            toolOptions.addView(actionButton("مستطيل", v ->
                    setStatus("اسحب لتحديد مستطيل")));
            toolOptions.addView(actionButton("تحديد الكل", v ->
                    applyRemote("select_all", new JSONObject())));
            toolOptions.addView(actionButton("إلغاء", v ->
                    applyRemote("select_none", new JSONObject())));
            toolOptions.addView(actionButton("توسيع", v ->
                    applyRemote("grow", json("steps", 5))));
            toolOptions.addView(actionButton("تقليص", v ->
                    applyRemote("shrink", json("steps", 5))));
        } else if ("transform".equals(id) || "perspective".equals(id)) {
            if (canvas != null) {
                canvas.setInteractionMode("transform_quad");
                canvas.resetTransformQuad();
            }

            toolOptions.addView(actionButton("تطبيق المقابض", v ->
                    applyTransformQuad()));
            toolOptions.addView(actionButton("إعادة ضبط", v -> {
                if (canvas != null) canvas.resetTransformQuad();
                setStatus("تمت إعادة مقابض التحويل إلى زوايا الصورة");
            }));
            toolOptions.addView(actionButton("تدوير 90°", v ->
                    applyRemote("rotate", json("degrees", 90))));
            toolOptions.addView(actionButton("تكبير/تصغير", v ->
                    showResizeDialog()));
            toolOptions.addView(actionButton("منظور رقمي", v ->
                    showPerspectiveDialog()));

            TextView help = label(
                    "اسحب أي مقبض أبيض من زوايا الصورة ثم اضغط تطبيق المقابض.",
                    11, MUTED, false);
            help.setPadding(dp(10), 0, dp(10), 0);
            toolOptions.addView(help, new LinearLayout.LayoutParams(dp(270), dp(82)));
        } else if ("gradient".equals(id)) {
            addSlider(toolOptions, "العتامة", 0, 100, 100, null);
            toolOptions.addView(actionButton("اللون 1 " + foregroundColor, v ->
                    showColorPaletteDialog("لون بداية التدرج", "gradient", "تدرج")));
            toolOptions.addView(actionButton("اللون 2 " + gradientBackgroundColor, v ->
                    showGradientBackgroundColorDialog()));
            toolOptions.addView(actionButton("التعرف على اللون 1", v ->
                    startColorPickFor("gradient", "تدرج")));
            toolOptions.addView(actionButton(
                    "linear".equals(gradientType) ? "خطي ✓" : "خطي",
                    v -> {
                        gradientType = "linear";
                        showTool("gradient", "تدرج", null);
                    }));
            toolOptions.addView(actionButton(
                    "radial".equals(gradientType) ? "دائري ✓" : "دائري",
                    v -> {
                        gradientType = "radial";
                        showTool("gradient", "تدرج", null);
                    }));
            addPointerModeControls(toolOptions);
        } else {
            TextView help = label(toolHelp(id), desktopLayout ? 13 : 11, MUTED, false);
            help.setPadding(dp(10), dp(6), dp(10), dp(6));
            if (desktopLayout) {
                toolOptions.addView(help);
            } else {
                toolOptions.addView(help, new LinearLayout.LayoutParams(dp(260), dp(82)));
            }
        }

        // On phones the bottom strip must contain ONLY the selected tool's
        // properties. Generic image color controls belong to the Colors menu,
        // otherwise tapping Text/Crop/Eraser appears to open the wrong panel.
        if (desktopLayout) {
            toolOptions.addView(divider());
            toolOptions.addView(label("ضبط سريع للصورة", 14, TEXT, true));
            addSlider(toolOptions, "السطوع", -100, 100, 0,
                    value -> applyRemote("brightness", json("value", value)));
            addSlider(toolOptions, "التباين", -100, 100, 0,
                    value -> applyRemote("contrast", json("value", value)));
            addSlider(toolOptions, "التشبع", -100, 100, 0,
                    value -> applyRemote("saturation", json("value", value)));
        }
    }

    private void applyTransformQuad() {
        if (canvas == null || !localEngine.hasImage()) return;
        float[] quad = canvas.getTransformQuad();
        if (quad == null || quad.length < 8) {
            toast("مقابض التحويل غير جاهزة");
            return;
        }

        JSONArray values = new JSONArray();
        try {
            for (float v : quad) values.put((double) v);
        } catch (Exception e) {
            toast("تعذر تجهيز نقاط التحويل");
            return;
        }
        applyRemote("quad_transform", jsonOf("quad", values));
    }

    private void showGradientBackgroundColorDialog() {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(6));

        GridLayout grid = new GridLayout(this);
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int paletteColumns = screenWidthDp < 380 ? 4 : (screenWidthDp < 480 ? 5 : 6);
        grid.setColumnCount(paletteColumns);
        int[] palette = {
                0xFF000000, 0xFFFFFFFF, 0xFF757575, 0xFFFF1744, 0xFFFF9100, 0xFFFFFF00,
                0xFF76FF03, 0xFF00E676, 0xFF1DE9B6, 0xFF00E5FF, 0xFF448AFF, 0xFF536DFE,
                0xFF7C4DFF, 0xFFE040FB, 0xFFFF4081, 0xFF795548, 0xFF263238, 0xFFB0BEC5
        };

        final AlertDialog[] holder = new AlertDialog[1];
        for (int color : palette) {
            Button swatch = new Button(this);
            swatch.setText("");
            swatch.setBackground(rounded(color, 6));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(42);
            lp.height = dp(42);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                gradientBackgroundColor = String.format("#%06X", 0xFFFFFF & color);
                if (holder[0] != null) holder[0].dismiss();
                showTool("gradient", "تدرج", null);
            });
            grid.addView(swatch);
        }
        box.addView(grid);

        EditText hex = new EditText(this);
        hex.setHint("#RRGGBB");
        hex.setText(gradientBackgroundColor);
        hex.setTextColor(TEXT);
        hex.setHintTextColor(MUTED);
        hex.setSingleLine(true);
        hex.setTextDirection(View.TEXT_DIRECTION_LTR);
        hex.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hex.setGravity(Gravity.CENTER);
        box.addView(hex);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("لون نهاية التدرج")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تم", null)
                .create();
        holder[0] = dialog;

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String value = hex.getText().toString().trim();
                    if (!value.startsWith("#")) value = "#" + value;
                    try {
                        Color.parseColor(value);
                        gradientBackgroundColor =
                                String.format("#%06X", 0xFFFFFF & Color.parseColor(value));
                        dialog.dismiss();
                        showTool("gradient", "تدرج", null);
                    } catch (Exception e) {
                        hex.setError("مثال: #000000");
                    }
                }));
        dialog.show();
    }

    private void addPointerModeControls(LinearLayout parent) {
        parent.addView(actionButton(
                pointerWorkEnabled ? "المقبض: تحريك فقط" : "المقبض: تحريك فقط ✓",
                v -> {
                    pointerWorkEnabled = false;
                    if (canvas != null) {
                        canvas.setPointerActionEnabled(false);
                        canvas.showPointerNow();
                    }
                    setStatus("تحريك فقط — اسحب المقبض البرتقالي ولن يحدث مسح أو تعبئة");
                }));
        parent.addView(actionButton(
                pointerWorkEnabled ? "المقبض: تحريك + تنفيذ ✓" : "المقبض: تحريك + تنفيذ",
                v -> {
                    pointerWorkEnabled = true;
                    if (canvas != null) {
                        canvas.setPointerActionEnabled(true);
                        canvas.showPointerNow();
                    }
                    setStatus("تحريك + تنفيذ — اسحب المقبض البرتقالي لتنفيذ الأداة من الرأس السماوي");
                }));
    }

    private void clearPendingSelectedTextColorPick() {
        selectedTextColorPickPending = false;
        selectedTextColorPickerInternalTransition = false;
        selectedTextDraftText = "";
        selectedTextDraftSize = "";
    }

    private void startColorPickFor(String returnTool, String returnLabel) {
        clearPendingSelectedTextColorPick();
        colorPickerReturnTool = returnTool;
        colorPickerReturnLabel = returnLabel;

        // Sampling is temporary and non-destructive. Do not overwrite the
        // user's shared move-only / move+execute preference.
        showTool("color_picker", "اختيار لون", null);
        if (canvas != null) {
            canvas.setPointerActionEnabled(false);
            canvas.showPointerNow();
        }
        setStatus("حرّك المؤشر فوق اللون ثم اضغط التقاط اللون");
    }

    private void pickColorAtPointer() {
        if (canvas == null) return;
        float[] p = canvas.getPointerImagePosition();
        if (p == null || p.length < 2) {
            toast("المؤشر غير جاهز");
            return;
        }
        pickLocalColor(p[0], p[1]);
    }

    private void rememberColor(String color) {
        if (color == null) return;
        String normalized = color.toUpperCase();
        recentColors.remove(normalized);
        recentColors.add(0, normalized);
        while (recentColors.size() > 8) recentColors.remove(recentColors.size() - 1);
    }

    private void applyForegroundColor(String color) {
        try {
            int parsed = Color.parseColor(color);
            foregroundColor = String.format("#%06X", 0xFFFFFF & parsed);
            rememberColor(foregroundColor);
            if (canvas != null) {
                canvas.setStrokePreview(brushSize, parsed);
            }
            setStatus("اللون الحالي " + foregroundColor);
        } catch (Exception e) {
            toast("لون غير صحيح");
        }
    }

    private String formatColorWithAlpha(int color) {
        return String.format("#%08X", color);
    }

    private void applyEraseFillTargetColor(String color) {
        try {
            int parsed = Color.parseColor(color);
            eraseFillTargetColor = formatColorWithAlpha(parsed);
            rememberColor(eraseFillTargetColor);
            if (canvas != null) {
                canvas.setStrokePreview(brushSize, parsed);
            }
            setStatus("اللون المستهدف " + eraseFillTargetColor);
        } catch (Exception e) {
            toast("لون مستهدف غير صحيح");
        }
    }

    private void startEraseFillTargetColorPick() {
        clearPendingSelectedTextColorPick();
        colorPickerReturnTool = "erase_target";
        colorPickerReturnLabel = "ممحاة/تعبئة";
        eraseTargetPickerInternalTransition = true;

        showTool("color_picker", "التقاط اللون المستهدف", null);
        if (canvas != null) {
            canvas.setPointerActionEnabled(false);
            canvas.setStrokePreview(brushSize, parseColorSafe(eraseFillTargetColor));
            canvas.showPointerNow();
        }
        setStatus("حرّك رأس المؤشر فوق اللون المطلوب ثم اضغط التقاط اللون");
    }

    private void showEraseFillTargetColorDialog() {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(6));

        TextView current = label("اللون المستهدف  " + eraseFillTargetColor,
                13, TEXT, true);
        current.setTextDirection(View.TEXT_DIRECTION_LTR);
        current.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        current.setGravity(Gravity.CENTER);
        current.setPadding(dp(8), dp(8), dp(8), dp(8));
        current.setBackground(rounded(parseColorSafe(eraseFillTargetColor), 10));
        current.setTextColor(colorLuminance(parseColorSafe(eraseFillTargetColor)) > 150
                ? Color.BLACK : Color.WHITE);
        box.addView(current, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        GridLayout grid = new GridLayout(this);
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int columns = screenWidthDp < 380 ? 4 : (screenWidthDp < 480 ? 5 : 6);
        grid.setColumnCount(columns);
        int[] palette = {
                0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF212121, 0xFF000000, 0xFFFF1744,
                0xFFFF5252, 0xFFFF8A80, 0xFFFF9100, 0xFFFFC400, 0xFFFFFF00, 0xFFCDDC39,
                0xFF76FF03, 0xFF00E676, 0xFF1DE9B6, 0xFF00E5FF, 0xFF40C4FF, 0xFF448AFF,
                0xFF536DFE, 0xFF7C4DFF, 0xFFB388FF, 0xFFE040FB, 0xFFFF4081, 0xFF795548
        };

        final AlertDialog[] holder = new AlertDialog[1];
        int swatchDp = screenWidthDp < 380 ? 42 : 44;
        for (int color : palette) {
            String hex = String.format("#%06X", 0xFFFFFF & color);
            Button swatch = new Button(this);
            swatch.setText("");
            swatch.setContentDescription("لون مستهدف " + hex);
            swatch.setBackground(rounded(color, 7));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(swatchDp);
            lp.height = dp(swatchDp);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                applyEraseFillTargetColor(hex);
                if (holder[0] != null) holder[0].dismiss();
                showTool("erase_fill", "ممحاة/تعبئة", null);
            });
            grid.addView(swatch);
        }
        box.addView(grid);

        EditText hexInput = new EditText(this);
        hexInput.setHint("#RRGGBB");
        hexInput.setText(eraseFillTargetColor);
        hexInput.setTextColor(TEXT);
        hexInput.setHintTextColor(MUTED);
        hexInput.setSingleLine(true);
        hexInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        hexInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hexInput.setGravity(Gravity.CENTER);
        box.addView(hexInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("اختيار اللون المستهدف")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("من الصورة", null)
                .setPositiveButton("تم", null)
                .create();
        holder[0] = dialog;

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = hexInput.getText().toString().trim();
                if (!value.startsWith("#")) value = "#" + value;
                try {
                    Color.parseColor(value);
                    applyEraseFillTargetColor(value);
                    dialog.dismiss();
                    showTool("erase_fill", "ممحاة/تعبئة", null);
                } catch (Exception e) {
                    hexInput.setError("مثال: #22235B");
                }
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                startEraseFillTargetColorPick();
            });
        });
        dialog.show();
    }

    private void showColorPaletteDialog(String title, String returnTool, String returnLabel) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(6));

        TextView current = label("اللون الحالي  " + foregroundColor, 13, TEXT, true);
        current.setTextDirection(View.TEXT_DIRECTION_LTR);
        current.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        current.setGravity(Gravity.CENTER);
        current.setPadding(dp(8), dp(8), dp(8), dp(8));
        current.setBackground(rounded(parseColorSafe(foregroundColor), 10));
        current.setTextColor(colorLuminance(parseColorSafe(foregroundColor)) > 150
                ? Color.BLACK : Color.WHITE);
        box.addView(current, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        int[] palette = {
                0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF212121, 0xFF000000, 0xFFFF1744,
                0xFFFF5252, 0xFFFF8A80, 0xFFFF9100, 0xFFFFC400, 0xFFFFFF00, 0xFFCDDC39,
                0xFF76FF03, 0xFF00E676, 0xFF1DE9B6, 0xFF00E5FF, 0xFF40C4FF, 0xFF448AFF,
                0xFF536DFE, 0xFF7C4DFF, 0xFFB388FF, 0xFFE040FB, 0xFFFF4081, 0xFF795548
        };

        final AlertDialog[] holder = new AlertDialog[1];
        for (int color : palette) {
            Button swatch = new Button(this);
            swatch.setText("");
            swatch.setBackground(rounded(color, 6));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(42);
            lp.height = dp(42);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                applyForegroundColor(String.format("#%06X", 0xFFFFFF & color));
                if ("text".equals(returnTool) && canvas != null && canvas.hasTextOverlay()) {
                    updateTextOverlayStyle();
                }
                if (holder[0] != null) holder[0].dismiss();
                if (returnTool != null) showTool(returnTool, returnLabel, null);
            });
            grid.addView(swatch);
        }
        box.addView(grid);

        if (!recentColors.isEmpty()) {
            TextView recentTitle = label("الألوان المستخدمة مؤخراً", 12, MUTED, false);
            recentTitle.setPadding(0, dp(8), 0, dp(4));
            box.addView(recentTitle);

            HorizontalScrollView recentScroll = new HorizontalScrollView(this);
            LinearLayout recentRow = new LinearLayout(this);
            recentRow.setOrientation(LinearLayout.HORIZONTAL);
            for (String rc : recentColors) {
                Button b = new Button(this);
                b.setText("");
                b.setBackground(rounded(parseColorSafe(rc), 20));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
                lp.setMargins(dp(3), 0, dp(3), 0);
                recentRow.addView(b, lp);
                b.setOnClickListener(v -> {
                    applyForegroundColor(rc);
                    if ("text".equals(returnTool) && canvas != null && canvas.hasTextOverlay()) {
                        updateTextOverlayStyle();
                    }
                    if (holder[0] != null) holder[0].dismiss();
                    if (returnTool != null) showTool(returnTool, returnLabel, null);
                });
            }
            recentScroll.addView(recentRow);
            box.addView(recentScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }

        EditText hex = new EditText(this);
        hex.setHint("#RRGGBB");
        hex.setText(foregroundColor);
        hex.setTextColor(TEXT);
        hex.setHintTextColor(MUTED);
        hex.setSingleLine(true);
        hex.setTextDirection(View.TEXT_DIRECTION_LTR);
        hex.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hex.setGravity(Gravity.CENTER);
        box.addView(hex);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("من الصورة", null)
                .setPositiveButton("تم", null)
                .create();
        holder[0] = dialog;

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = hex.getText().toString().trim();
                if (!value.startsWith("#")) value = "#" + value;
                try {
                    Color.parseColor(value);
                    applyForegroundColor(value);
                    if ("text".equals(returnTool) && canvas != null && canvas.hasTextOverlay()) {
                        updateTextOverlayStyle();
                    }
                    dialog.dismiss();
                    if (returnTool != null) showTool(returnTool, returnLabel, null);
                } catch (Exception e) {
                    hex.setError("مثال: #2B2E33");
                }
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                startColorPickFor(returnTool, returnLabel);
            });
        });
        dialog.show();
    }

    private void applyEraseFillToSelection() {
        if (!localEngine.hasImage()) {
            toast("افتح صورة أولاً");
            return;
        }
        if (!localEngine.hasSelection()) {
            toast("حدد المنطقة أولاً");
            setStatus("لا توجد منطقة محددة");
            return;
        }
        if (!eraseFillMatchColor) {
            toast("فعّل حسب اللون أولاً");
            return;
        }

        setStatus(("eraser".equals(eraseFillMode)
                ? (eraserTransparent
                    ? "جاري إزالة اللون المستهدف وترميم الخلفية داخل التحديد..."
                    : "جاري استبدال اللون المستهدف داخل التحديد...")
                : "جاري تعبئة اللون المستهدف داخل كامل التحديد..."));

        applyRemote("color_match_selection", jsonOf(
                "mode", eraseFillMode,
                "opacity", brushOpacity,
                "color", foregroundColor,
                "transparent", eraserTransparent,
                "restore_background",
                        "eraser".equals(eraseFillMode) && eraserTransparent,
                "target_color", eraseFillTargetColor,
                "tolerance", eraseFillTolerance
        ));
    }

    private void handleCanvasStroke(float[] points) {
        if (canvas == null || points == null || points.length < 2) return;

        if (projectId == null) {
            canvas.clearStrokePreview();
            toast("افتح صورة أولاً");
            return;
        }

        if (busy) {
            canvas.clearStrokePreview();
            setStatus("انتظر انتهاء العملية الحالية");
            return;
        }

        lastImageTapX = points[points.length - 2];
        lastImageTapY = points[points.length - 1];

        if ("color_picker".equals(activeTool)) {
            pickLocalColor(lastImageTapX, lastImageTapY);
            canvas.clearStrokePreview();
            return;
        }

        if ("text_box".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            float x0 = points[0];
            float y0 = points[1];
            float x1 = points[points.length - 2];
            float y1 = points[points.length - 1];
            RectF region = new RectF(
                    Math.min(x0, x1),
                    Math.min(y0, y1),
                    Math.max(x0, x1),
                    Math.max(y0, y1));
            if (region.width() < 2f || region.height() < 2f) {
                canvas.clearStrokePreview();
                toast("ارسم إطارًا حول النص");
                return;
            }
            selectedTextRegion = region;
            recognizedText = "";
            recognizedTextConfidence = 0;
            detectedTextRegions.add(new RectF(region));
            canvas.setDetectedTextRegions(detectedTextRegions);
            canvas.setSelectedTextRegion(region);
            canvas.setInteractionMode("text_select");
            activeTool = "text_detect";
            canvas.clearStrokePreview();
            setStatus("تم تحديد النص يدويًا — يمكنك التعرف عليه أو تعديله أو قصه");
            return;
        }

        if ("text".equals(activeTool) && "text_select".equals(canvas.getInteractionMode())) {
            selectDetectedTextRegion(lastImageTapX, lastImageTapY);
            canvas.clearStrokePreview();
            return;
        }

        if ("fuzzy_select".equals(activeTool)) {
            double threshold = Math.max(0.01, Math.min(1.0, selectionThreshold / 100.0));
            applyRemote("select_contiguous", jsonOf(
                    "x", lastImageTapX,
                    "y", lastImageTapY,
                    "threshold", threshold));
            return;
        }

        if ("color_select".equals(activeTool)) {
            double threshold = Math.max(0.01, Math.min(1.0, selectionThreshold / 100.0));
            applyRemote("select_color", jsonOf(
                    "color", foregroundColor,
                    "threshold", threshold));
            return;
        }

        if ("select".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            float x0 = points[0];
            float y0 = points[1];
            float x1 = points[points.length - 2];
            float y1 = points[points.length - 1];
            int x = Math.round(Math.min(x0, x1));
            int y = Math.round(Math.min(y0, y1));
            int w = Math.max(1, Math.round(Math.abs(x1 - x0)));
            int h = Math.max(1, Math.round(Math.abs(y1 - y0)));
            applyRemote("select_rectangle", jsonOf(
                    "x", x, "y", y, "width", w, "height", h));
            return;
        }

        if ("crop".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            float x0 = points[0];
            float y0 = points[1];
            float x1 = points[points.length - 2];
            float y1 = points[points.length - 1];

            int x = Math.round(Math.min(x0, x1));
            int y = Math.round(Math.min(y0, y1));
            int w = Math.max(1, Math.round(Math.abs(x1 - x0)));
            int h = Math.max(1, Math.round(Math.abs(y1 - y0)));

            if (cropAspectRatio > 0f) {
                float current = w / (float) Math.max(1, h);
                if (current > cropAspectRatio) {
                    w = Math.max(1, Math.round(h * cropAspectRatio));
                } else {
                    h = Math.max(1, Math.round(w / cropAspectRatio));
                }
            }

            if (localEngine.hasImage()) {
                w = Math.min(w, Math.max(1, localEngine.width() - x));
                h = Math.min(h, Math.max(1, localEngine.height() - y));
            }

            applyRemote("crop", jsonOf("x", x, "y", y, "width", w, "height", h));
            return;
        }

        if ("free_select".equals(activeTool)) {
            if (points.length < 6) {
                canvas.clearStrokePreview();
                setStatus("ارسم ثلاث نقاط على الأقل للتحديد الحر");
                return;
            }
            applyRemote("select_polygon", jsonOf("points", pointsJson(points)));
            return;
        }

        if ("gradient".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            applyRemote("gradient", jsonOf(
                    "x1", points[0],
                    "y1", points[1],
                    "x2", points[points.length - 2],
                    "y2", points[points.length - 1],
                    "type", gradientType,
                    "foreground", foregroundColor,
                    "background", gradientBackgroundColor
            ));
            return;
        }

        if ("clone".equals(activeTool) || "heal".equals(activeTool)) {
            if (Float.isNaN(cloneSourceX) || Float.isNaN(cloneSourceY)) {
                cloneSourceX = points[0];
                cloneSourceY = points[1];
                canvas.clearStrokePreview();
                setStatus("تم تحديد المصدر عند X " + Math.round(cloneSourceX) +
                        " Y " + Math.round(cloneSourceY) + " — ارسم الآن على الهدف");
                return;
            }

            applyRemote(activeTool, jsonOf(
                    "source_x", cloneSourceX,
                    "source_y", cloneSourceY,
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity
            ));
            return;
        }

        if ("erase_fill".equals(activeTool)) {
            if (eraseFillMatchColor && localEngine.hasSelection()) {
                // A committed selection defines the whole work area. A tap or
                // short drag now applies the target-color operation to all of
                // it instead of limiting the edit to the brush footprint.
                canvas.clearStrokePreview();
                applyEraseFillToSelection();
                return;
            }

            if ("fill".equals(eraseFillMode)) {
                applyRemote("fill_stroke", jsonOf(
                        "points", pointsJson(points),
                        "size", brushSize,
                        "opacity", brushOpacity,
                        "color", foregroundColor,
                        "match_color", eraseFillMatchColor,
                        "target_color", eraseFillTargetColor,
                        "tolerance", eraseFillTolerance
                ));
            } else {
                applyRemote("eraser", jsonOf(
                        "points", pointsJson(points),
                        "size", brushSize,
                        "opacity", brushOpacity,
                        "color", foregroundColor,
                        "transparent", eraserTransparent,
                        "match_color", eraseFillMatchColor,
                        "target_color", eraseFillTargetColor,
                        "tolerance", eraseFillTolerance
                ));
            }
            return;
        }

        if ("brush".equals(activeTool)) {
            applyRemote("paintbrush", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity,
                    "color", foregroundColor
            ));
            return;
        }

        if ("pencil".equals(activeTool)) {
            applyRemote("pencil", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity,
                    "color", foregroundColor
            ));
            return;
        }

        if ("smudge".equals(activeTool)) {
            applyRemote("smudge", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "pressure", brushOpacity
            ));
            return;
        }

        if ("dodge_burn".equals(activeTool)) {
            applyRemote("dodge_burn", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "exposure", brushOpacity,
                    "type", dodgeBurnType,
                    "range", "midtones"
            ));
            return;
        }

        canvas.clearStrokePreview();
    }

    private void setCloneSourceAtPointer() {
        if (canvas == null) return;
        float[] p = canvas.getPointerImagePosition();
        if (p == null || p.length < 2) {
            toast("المؤشر غير جاهز");
            return;
        }
        cloneSourceX = p[0];
        cloneSourceY = p[1];
        setStatus("تم أخذ المصدر من المؤشر • X " + Math.round(cloneSourceX) +
                " Y " + Math.round(cloneSourceY));
    }

    private JSONArray pointsJson(float[] points) {
        JSONArray array = new JSONArray();
        if (points != null) {
            for (float point : points) {
                try {
                    array.put((double) point);
                } catch (Exception ignored) {}
            }
        }
        return array;
    }

    private void pickLocalColor(float imageX, float imageY) {
        if (canvas == null || canvas.getBitmap() == null) return;
        Bitmap bitmap = canvas.getBitmap();
        int x = Math.max(0, Math.min(bitmap.getWidth() - 1, Math.round(imageX)));
        int y = Math.max(0, Math.min(bitmap.getHeight() - 1, Math.round(imageY)));
        int color = bitmap.getPixel(x, y);

        if ("erase_target".equals(colorPickerReturnTool)) {
            String sampled = formatColorWithAlpha(color);
            applyEraseFillTargetColor(sampled);
            colorPickerReturnTool = null;
            colorPickerReturnLabel = null;
            eraseTargetPickerInternalTransition = false;
            showTool("erase_fill", "ممحاة/تعبئة", null);
            setStatus("تم التقاط اللون المستهدف " + eraseFillTargetColor);
            return;
        }

        boolean textColorTarget = "text".equals(colorPickerReturnTool);
        foregroundColor = String.format("#%06X", (0xFFFFFF & color));
        rememberColor(foregroundColor);
        canvas.setStrokePreview(brushSize, color);
        if (textColorTarget && canvas.hasTextOverlay()) {
            updateTextOverlayStyle();
        }
        setStatus("اللون الحالي " + foregroundColor + "  •  X " + x + " Y " + y);

        if (selectedTextColorPickPending) {
            selectedTextColorPickPending = false;
            selectedTextColorPickerInternalTransition = false;
            String draftText = selectedTextDraftText;
            String draftSize = selectedTextDraftSize;
            selectedTextDraftText = "";
            selectedTextDraftSize = "";
            colorPickerReturnTool = null;
            colorPickerReturnLabel = null;
            showTool("text", "نص", null);
            showEditRecognizedTextDialog(draftText, draftSize);
            setStatus("تم التقاط لون النص " + foregroundColor + " — اضغط استبدال للتطبيق");
            return;
        }

        if (colorPickerReturnTool != null) {
            String returnTool = colorPickerReturnTool;
            String returnLabel = colorPickerReturnLabel == null ? "الأداة" : colorPickerReturnLabel;
            colorPickerReturnTool = null;
            colorPickerReturnLabel = null;
            showTool(returnTool, returnLabel, null);
        }
    }

    private void startManualTextSelection() {
        if (!localEngine.hasImage() || canvas == null) {
            toast("افتح صورة أولاً");
            return;
        }
        activeTool = "text_box";
        canvas.setInteractionMode("select");
        canvas.setPointerActionEnabled(true);
        setStatus("اسحب مستطيلاً حول النص المطلوب");
    }

    private void detectTextRegions() {
        if (!localEngine.hasImage() || busy) {
            if (!localEngine.hasImage()) toast("افتح صورة أولاً");
            return;
        }

        setBusy(true);
        setStatus("جاري اكتشاف كل النصوص محليًا...");

        localExecutor.execute(() -> {
            try {
                Bitmap source = localEngine.current();

                List<RectF> ocrRegions = new ArrayList<>();
                try {
                    ocrRegions.addAll(ocrEngine.detectWordRegions(source));
                } catch (Exception ignoredOcrDetection) {
                    // The edge detector below remains available as an offline fallback.
                }

                List<RectF> fallbackRegions = textRegionDetector.detect(source);
                List<RectF> regions = combineDetectedTextRegions(
                        ocrRegions, fallbackRegions, source.getWidth(), source.getHeight());

                runOnUiThread(() -> {
                    detectedTextRegions.clear();
                    detectedTextRegions.addAll(regions);
                    selectedTextRegion = null;
                    recognizedText = "";
                    recognizedTextConfidence = 0;
                    activeTool = "text_detect";
                    if (canvas != null) {
                        canvas.setDetectedTextRegions(regions);
                        canvas.setInteractionMode("text_select");
                        // Text detection becomes immediately actionable only
                        // for the current text-select interaction. Do not mutate
                        // the shared pointer preference used by brush/eraser/etc.
                        canvas.setPointerActionEnabled(true);
                        canvas.showPointerNow();
                    }
                    setBusy(false);
                    setStatus("تم اكتشاف " + regions.size() +
                            " منطقة نص — اضغط مباشرة على أي إطار أزرق لتحديده");
                    addHistory("كشف النصوص " + regions.size());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("فشل اكتشاف النص");
                    toast(shortText(e.getMessage()));
                });
            }
        });
    }

    private List<RectF> combineDetectedTextRegions(List<RectF> ocr,
                                                   List<RectF> fallback,
                                                   int imageWidth,
                                                   int imageHeight) {
        List<RectF> result = new ArrayList<>();
        if (ocr != null) {
            for (RectF box : ocr) {
                if (isUsefulTextRegion(box, imageWidth, imageHeight)) {
                    result.add(new RectF(box));
                }
            }
        }

        if (fallback != null) {
            for (RectF box : fallback) {
                if (!isUsefulTextRegion(box, imageWidth, imageHeight)) continue;

                boolean alreadyCovered = false;
                for (RectF existing : result) {
                    float intersection = rectIntersectionArea(box, existing);
                    if (intersection <= 0f) continue;
                    float smaller = Math.max(1f, Math.min(
                            box.width() * box.height(),
                            existing.width() * existing.height()));
                    if (intersection / smaller >= 0.35f) {
                        alreadyCovered = true;
                        break;
                    }
                }

                if (!alreadyCovered) result.add(new RectF(box));
            }
        }

        result.sort((a, b) -> {
            int top = Float.compare(a.top, b.top);
            return top != 0 ? top : Float.compare(a.left, b.left);
        });
        return result;
    }

    private boolean isUsefulTextRegion(RectF box, int imageWidth, int imageHeight) {
        if (box == null || box.width() < 4f || box.height() < 4f) return false;
        float area = box.width() * box.height();
        float imageArea = Math.max(1f, imageWidth * (float) imageHeight);
        return area < imageArea * 0.20f;
    }

    private float rectIntersectionArea(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0f;
        return (right - left) * (bottom - top);
    }

    private void selectDetectedTextRegion(float imageX, float imageY) {
        RectF best = null;
        float bestArea = Float.MAX_VALUE;

        // Pass 1: exact containment always wins. This prevents a smaller
        // neighboring OCR box's padded halo from stealing a tap that is
        // genuinely inside another word.
        for (RectF region : detectedTextRegions) {
            if (!region.contains(imageX, imageY)) continue;
            float area = Math.max(1f, region.width() * region.height());
            if (area < bestArea) {
                bestArea = area;
                best = region;
            }
        }

        // Pass 2: only when there is no exact hit, use a forgiving touch halo
        // for tiny words/numbers that are difficult to hit precisely.
        if (best == null) {
            float bestScore = Float.MAX_VALUE;
            for (RectF region : detectedTextRegions) {
                float zoom = canvas == null ? 1f : Math.max(0.05f, canvas.getZoom());
                // Keep a finger-sized halo stable on screen regardless of zoom.
                float halo = dp(16) / zoom;
                RectF expanded = new RectF(
                        region.left - halo,
                        region.top - halo,
                        region.right + halo,
                        region.bottom + halo);
                if (!expanded.contains(imageX, imageY)) continue;

                float area = Math.max(1f, region.width() * region.height());
                float dx = imageX - region.centerX();
                float dy = imageY - region.centerY();
                float distancePenalty = dx * dx + dy * dy;
                float score = area + distancePenalty * 0.25f;
                if (score < bestScore) {
                    bestScore = score;
                    best = region;
                }
            }
        }

        selectedTextRegion = best == null ? null : new RectF(best);
        recognizedText = "";
        recognizedTextConfidence = 0;
        if (canvas != null) canvas.setSelectedTextRegion(selectedTextRegion);

        if (selectedTextRegion == null) {
            setStatus("لم يتم تحديد نص — اضغط على أحد الإطارات الزرقاء");
            toast("اضغط مباشرة داخل إطار النص الأزرق");
        } else {
            setStatus("تم تحديد النص • " +
                    Math.round(selectedTextRegion.width()) + "×" +
                    Math.round(selectedTextRegion.height()) +
                    " — الآن استخدم تعديل المحدد أو قص المحدد أو حذف");
        }
    }

    private boolean selectTextAtPointerNow() {
        if (canvas == null) return false;
        float[] p = canvas.getPointerImagePosition();
        if (p == null || p.length < 2) return false;
        selectDetectedTextRegion(p[0], p[1]);
        return selectedTextRegion != null;
    }

    private void selectTextAtPointer() {
        if (!selectTextAtPointerNow()) {
            toast("ضع رأس المؤشر داخل إطار النص أو اضغط على الإطار مباشرة");
        }
    }

    private boolean ensureSelectedTextRegion() {
        return selectedTextRegion != null || selectTextAtPointerNow();
    }

    private void recognizeSelectedText(boolean openEditorAfter) {
        if (!ensureSelectedTextRegion()) {
            toast("اضغط على إطار النص أو ضع رأس المؤشر داخله");
            return;
        }
        if (busy) return;

        RectF region = new RectF(selectedTextRegion);
        setBusy(true);
        setStatus("جاري التعرف على النص محليًا...");

        localExecutor.execute(() -> {
            try {
                LocalOcrEngine.Result result = ocrEngine.recognize(localEngine.current(), region);
                runOnUiThread(() -> {
                    setBusy(false);
                    recognizedText = result.text;
                    recognizedTextConfidence = result.confidence;

                    if (recognizedText.isEmpty()) {
                        setStatus("لم يتمكن OCR من قراءة النص المحدد");
                        toast("لم يتم التعرف على نص واضح");
                        if (openEditorAfter) showEditRecognizedTextDialog("");
                        return;
                    }

                    setStatus("OCR " + result.confidence + "% • " + shortText(recognizedText));
                    if (openEditorAfter) {
                        showEditRecognizedTextDialog(recognizedText);
                    } else {
                        showRecognizedTextDialog(result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("فشل التعرف على النص");
                    toast(shortText(e.getMessage()));
                });
            }
        });
    }

    private void showRecognizedTextDialog(LocalOcrEngine.Result result) {
        TextView value = label(result.text.isEmpty() ? "لم يتم التعرف على نص" : result.text,
                15, TEXT, false);
        value.setPadding(dp(18), dp(16), dp(18), dp(16));
        value.setTextIsSelectable(true);

        new AlertDialog.Builder(this)
                .setTitle("النص المكتشف • دقة " + result.confidence + "%")
                .setView(value)
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("تعديل", (d, w) ->
                        showEditRecognizedTextDialog(result.text))
                .show();
    }

    private void showEditRecognizedTextDialog(String initialText) {
        showEditRecognizedTextDialog(initialText, null);
    }

    private void showEditRecognizedTextDialog(String initialText, String initialSize) {
        if (selectedTextRegion == null) {
            toast("حدد النص أولاً");
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText textInput = new EditText(this);
        textInput.setHint("النص الجديد");
        textInput.setText(initialText == null ? "" : initialText);
        textInput.setTextColor(TEXT);
        textInput.setHintTextColor(MUTED);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setMinLines(2);
        box.addView(textInput);

        EditText sizeInput = new EditText(this);
        sizeInput.setHint("حجم الخط");
        int suggestedSize = Math.max(8, Math.round(selectedTextRegion.height() * 0.70f));
        sizeInput.setText(initialSize == null || initialSize.trim().isEmpty()
                ? String.valueOf(suggestedSize)
                : initialSize);
        sizeInput.setTextColor(TEXT);
        sizeInput.setHintTextColor(MUTED);
        sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(sizeInput);

        TextView colorPreview = label("", 13, TEXT, true);
        colorPreview.setGravity(Gravity.CENTER);
        colorPreview.setPadding(dp(10), dp(8), dp(10), dp(8));
        updateSelectedTextColorPreview(colorPreview);
        box.addView(colorPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout colorActions = new LinearLayout(this);
        colorActions.setOrientation(LinearLayout.HORIZONTAL);
        colorActions.setGravity(Gravity.CENTER);
        colorActions.setPadding(0, dp(6), 0, dp(4));

        Button chooseColor = new Button(this);
        chooseColor.setText("اختيار اللون");
        chooseColor.setAllCaps(false);

        Button pickColor = new Button(this);
        pickColor.setText("التقاط اللون من الصورة");
        pickColor.setAllCaps(false);

        colorActions.addView(chooseColor, new LinearLayout.LayoutParams(
                0, dp(52), 1f));
        colorActions.addView(pickColor, new LinearLayout.LayoutParams(
                0, dp(52), 1f));
        box.addView(colorActions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("تعديل النص المحدد")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("حذف", null)
                .setPositiveButton("استبدال", null)
                .create();

        chooseColor.setOnClickListener(v ->
                showSelectedTextColorPalette(colorPreview));

        pickColor.setOnClickListener(v -> {
            selectedTextDraftText = textInput.getText().toString();
            selectedTextDraftSize = sizeInput.getText().toString();
            selectedTextColorPickPending = true;
            selectedTextColorPickerInternalTransition = true;
            colorPickerReturnTool = null;
            colorPickerReturnLabel = null;
            dialog.dismiss();

            // Disable execution only on the temporary picker canvas; keep the
            // user's stored pointer preference for all other tools.
            showTool("color_picker", "اختيار لون النص", null);
            if (canvas != null) {
                canvas.setPointerActionEnabled(false);
                canvas.showPointerNow();
            }
            setStatus("حرّك رأس المؤشر فوق لون النص المطلوب ثم اضغط التقاط اللون");
        });

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                double size = suggestedSize;
                try {
                    size = Double.parseDouble(sizeInput.getText().toString());
                } catch (Exception ignoredSize) {}

                String replacement = textInput.getText().toString();
                dialog.dismiss();
                replaceSelectedTextRegion(replacement, size);
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                deleteSelectedTextRegion();
            });
        });

        dialog.show();
    }

    private void updateSelectedTextColorPreview(TextView preview) {
        if (preview == null) return;
        int parsed = parseColorSafe(foregroundColor);
        preview.setText("لون النص الحالي  " + foregroundColor.toUpperCase());
        preview.setTextDirection(View.TEXT_DIRECTION_LTR);
        preview.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        preview.setBackground(rounded(parsed, 10));
        preview.setTextColor(colorLuminance(parsed) > 150 ? Color.BLACK : Color.WHITE);
    }

    private void showSelectedTextColorPalette(TextView preview) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(6));

        TextView current = label("", 13, TEXT, true);
        current.setGravity(Gravity.CENTER);
        current.setPadding(dp(8), dp(8), dp(8), dp(8));
        updateSelectedTextColorPreview(current);
        box.addView(current, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        GridLayout grid = new GridLayout(this);
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int paletteColumns = screenWidthDp < 380 ? 4 : (screenWidthDp < 480 ? 5 : 6);
        grid.setColumnCount(paletteColumns);
        int[] palette = {
                0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF212121, 0xFF000000, 0xFFFF1744,
                0xFFFF5252, 0xFFFF8A80, 0xFFFF9100, 0xFFFFC400, 0xFFFFFF00, 0xFFCDDC39,
                0xFF76FF03, 0xFF00E676, 0xFF1DE9B6, 0xFF00E5FF, 0xFF40C4FF, 0xFF448AFF,
                0xFF536DFE, 0xFF7C4DFF, 0xFFB388FF, 0xFFE040FB, 0xFFFF4081, 0xFF795548
        };

        final AlertDialog[] holder = new AlertDialog[1];
        int swatchDp = screenWidthDp < 380 ? 42 : 44;
        for (int c : palette) {
            Button swatch = new Button(this);
            swatch.setText("");
            String swatchHex = String.format("#%06X", 0xFFFFFF & c);
            swatch.setContentDescription("لون " + swatchHex);
            swatch.setBackground(rounded(c, 7));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(swatchDp);
            lp.height = dp(swatchDp);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                applyForegroundColor(swatchHex);
                updateSelectedTextColorPreview(preview);
                updateSelectedTextColorPreview(current);
                if (holder[0] != null) holder[0].dismiss();
            });
            grid.addView(swatch);
        }
        box.addView(grid);

        EditText hex = new EditText(this);
        hex.setHint("#RRGGBB");
        hex.setText(foregroundColor);
        hex.setTextColor(TEXT);
        hex.setHintTextColor(MUTED);
        hex.setSingleLine(true);
        hex.setTextDirection(View.TEXT_DIRECTION_LTR);
        hex.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hex.setGravity(Gravity.CENTER);
        box.addView(hex);

        AlertDialog paletteDialog = new AlertDialog.Builder(this)
                .setTitle("اختيار لون النص")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", null)
                .create();
        holder[0] = paletteDialog;

        paletteDialog.setOnShowListener(ignored ->
                paletteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String value = hex.getText().toString().trim();
                    if (!value.startsWith("#")) value = "#" + value;
                    try {
                        Color.parseColor(value);
                        applyForegroundColor(value);
                        updateSelectedTextColorPreview(preview);
                        updateSelectedTextColorPreview(current);
                        paletteDialog.dismiss();
                    } catch (Exception e) {
                        hex.setError("مثال: #FFFFFF");
                    }
                }));
        paletteDialog.show();
    }

    private void replaceSelectedTextRegion(String replacement, double size) {
        if (selectedTextRegion == null) return;

        RectF region = new RectF(selectedTextRegion);
        String background = sampleRegionBackground(region);

        double defaultSize = Math.max(8.0, region.height() * 0.70);
        double sizeRatio = Math.max(0.35, Math.min(3.0, size / defaultSize));

        applyRemote("replace_text_region", jsonOf(
                "x", Math.round(region.left),
                "y", Math.round(region.top),
                "width", Math.max(1, Math.round(region.width())),
                "height", Math.max(1, Math.round(region.height())),
                "text", replacement == null ? "" : replacement,
                "size", size,
                "size_ratio", sizeRatio,
                "auto_match", true,
                "font", textFontFamily,
                "weight", textWeight,
                "bold", textWeight >= 700,
                "width_scale", textWidthPercent / 100.0,
                "color", foregroundColor,
                "background", background
        ));
    }

    private void deleteSelectedTextRegion() {
        if (!ensureSelectedTextRegion()) {
            toast("اضغط على إطار النص أو ضع رأس المؤشر داخله ثم اضغط حذف");
            return;
        }
        replaceSelectedTextRegion("", Math.max(8, selectedTextRegion.height() * 0.7));
    }

    private String sampleRegionBackground(RectF region) {
        if (canvas == null || canvas.getBitmap() == null) return "#FFFFFF";
        Bitmap bitmap = canvas.getBitmap();

        int left = Math.max(0, Math.min(bitmap.getWidth() - 1, Math.round(region.left)));
        int top = Math.max(0, Math.min(bitmap.getHeight() - 1, Math.round(region.top)));
        int right = Math.max(left, Math.min(bitmap.getWidth() - 1, Math.round(region.right)));
        int bottom = Math.max(top, Math.min(bitmap.getHeight() - 1, Math.round(region.bottom)));

        long rr = 0, gg = 0, bb = 0, count = 0;
        int stepX = Math.max(1, (right - left) / 24);
        int stepY = Math.max(1, (bottom - top) / 12);
        int pad = 2;

        int yTop = Math.max(0, top - pad);
        int yBottom = Math.min(bitmap.getHeight() - 1, bottom + pad);
        for (int x = left; x <= right; x += stepX) {
            int c1 = bitmap.getPixel(x, yTop);
            int c2 = bitmap.getPixel(x, yBottom);
            rr += Color.red(c1) + Color.red(c2);
            gg += Color.green(c1) + Color.green(c2);
            bb += Color.blue(c1) + Color.blue(c2);
            count += 2;
        }

        int xLeft = Math.max(0, left - pad);
        int xRight = Math.min(bitmap.getWidth() - 1, right + pad);
        for (int y = top; y <= bottom; y += stepY) {
            int c1 = bitmap.getPixel(xLeft, y);
            int c2 = bitmap.getPixel(xRight, y);
            rr += Color.red(c1) + Color.red(c2);
            gg += Color.green(c1) + Color.green(c2);
            bb += Color.blue(c1) + Color.blue(c2);
            count += 2;
        }

        if (count == 0) return "#FFFFFF";
        int color = Color.rgb((int) (rr / count), (int) (gg / count), (int) (bb / count));
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private void cropSelectedTextRegion() {
        if (!ensureSelectedTextRegion()) {
            toast("اضغط على إطار النص أو ضع رأس المؤشر داخله ثم اضغط قص المحدد");
            return;
        }

        int x = Math.max(0, Math.round(selectedTextRegion.left));
        int y = Math.max(0, Math.round(selectedTextRegion.top));
        int w = Math.max(1, Math.round(selectedTextRegion.width()));
        int h = Math.max(1, Math.round(selectedTextRegion.height()));

        applyRemote("crop", jsonOf("x", x, "y", y, "width", w, "height", h));
    }

    private int colorLuminance(int color) {
        return Math.round(Color.red(color) * 0.299f +
                Color.green(color) * 0.587f +
                Color.blue(color) * 0.114f);
    }

    private int parseColorSafe(String color) {
        try {
            return Color.parseColor(color);
        } catch (Exception ignored) {
            return Color.WHITE;
        }
    }

    private void showAddTextDialog(float x, float y) {
        if (projectId == null || !localEngine.hasImage()) {
            toast("افتح صورة أولاً");
            return;
        }
        if (canvas != null && canvas.hasTextOverlay()) {
            new AlertDialog.Builder(this)
                    .setTitle("يوجد نص غير مثبت")
                    .setMessage("ثبّت النص الحالي أو ألغِه قبل إضافة نص جديد حتى لا تفقد موضعه وتعديلاتك.")
                    .setNegativeButton("إغلاق", null)
                    .setPositiveButton("تثبيت الحالي", (d, w) -> commitTextOverlay())
                    .show();
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText textInput = new EditText(this);
        textInput.setHint("اكتب أو الصق النص");
        textInput.setTextColor(TEXT);
        textInput.setHintTextColor(MUTED);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setMinLines(2);
        textInput.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        box.addView(textInput);

        TextView fontInfo = label(
                "الخط: " + textFontLabel + " • الحجم " + textSize +
                        " • العرض " + textWidthPercent + "% • السماكة " + textWeight,
                12, MUTED, false);
        fontInfo.setPadding(0, dp(8), 0, dp(4));
        fontInfo.setTypeface(weightedTypeface(textFontFamily, textWeight));
        box.addView(fontInfo);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إضافة نص قابل للتحريك")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("وضع على الصورة", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = textInput.getText().toString();
                    if (value.trim().isEmpty()) {
                        textInput.setError("اكتب النص");
                        return;
                    }

                    float px = x;
                    float py = y;
                    if (px <= 0f && py <= 0f) {
                        px = localEngine.width() / 2f;
                        py = localEngine.height() / 2f;
                    }

                    dialog.dismiss();
                    canvas.startTextOverlay(
                            value,
                            px,
                            py,
                            textSize,
                            parseColorSafe(foregroundColor),
                            textFontFamily,
                            textWeight,
                            textWidthPercent / 100f);
                    activeTool = "text";
                    setStatus("حرّك النص ثم كبّره أو دوّره، وبعدها اضغط تثبيت النص");
                    showTool("text", "نص", null);
                }));
        dialog.show();
    }

    private void updateTextOverlayStyle() {
        if (canvas == null || !canvas.hasTextOverlay()) return;
        canvas.updateTextOverlayStyle(
                textSize,
                parseColorSafe(foregroundColor),
                textFontFamily,
                textWeight,
                textWidthPercent / 100f);
    }

    private void commitTextOverlay() {
        if (canvas == null || !canvas.hasTextOverlay()) {
            toast("أضف نصًا أولاً");
            return;
        }
        if (busy) {
            toast("انتظر انتهاء العملية الحالية ثم ثبّت النص");
            return;
        }

        JSONObject params = jsonOf(
                "text", canvas.getTextOverlayText(),
                "x", canvas.getTextOverlayX(),
                "y", canvas.getTextOverlayY(),
                "size", canvas.getTextOverlaySize(),
                "scale", canvas.getTextOverlayScale(),
                "rotation", canvas.getTextOverlayRotation(),
                "font", canvas.getTextOverlayFont(),
                "weight", canvas.getTextOverlayWeight(),
                "bold", canvas.isTextOverlayBold(),
                "width_scale", canvas.getTextOverlayWidthScale(),
                "color", String.format("#%08X", canvas.getTextOverlayColor())
        );

        applyRemote("add_text", params);
        setStatus("جاري تثبيت النص على الطبقة...");
    }

    private void showFontPickerDialog() {
        final String[] labels = {
                "عربي حديث", "عربي متوسط", "عربي كتابي", "عربي عريض",
                "عربي رفيع", "عربي مضغوط", "أحادي المسافة"
        };
        final String[] families = {
                "sans-serif", "sans-serif-medium", "serif", "sans-serif-black",
                "sans-serif-light", "sans-serif-condensed", "monospace"
        };

        LinearLayout list = column();
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("اختيار الخط")
                .setView(list)
                .setNegativeButton("إغلاق", null)
                .create();

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button b = actionButton(labels[i] + "  —  أبجد هوز 123", v -> {
                textFontLabel = labels[index];
                textFontFamily = families[index];
                updateTextOverlayStyle();
                dialog.dismiss();
                showTool("text", "نص", null);
                setStatus("تم اختيار خط " + textFontLabel);
            });
            b.setTypeface(weightedTypeface(families[i], textWeight));
            list.addView(b);
        }
        dialog.show();
    }

    private void addFontPresetButtons(LinearLayout parent) {
        String[] labels = {
                "عربي حديث", "عربي متوسط", "عربي كتابي",
                "عربي عريض", "عربي رفيع", "عربي مضغوط"
        };
        String[] families = {
                "sans-serif", "sans-serif-medium", "serif",
                "sans-serif-black", "sans-serif-light", "sans-serif-condensed"
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button b = actionButton(labels[i] + " — العربية ١٢٣", v -> {
                textFontLabel = labels[index];
                textFontFamily = families[index];
                updateTextOverlayStyle();
                setStatus("الخط الحالي: " + textFontLabel);
            });
            b.setTypeface(weightedTypeface(families[i], textWeight));
            parent.addView(b);
        }
    }

    private Typeface weightedTypeface(String family, int weight) {
        String safeFamily = family == null || family.trim().isEmpty()
                ? "sans-serif" : family;
        Typeface base = Typeface.create(safeFamily, Typeface.NORMAL);
        int safeWeight = Math.max(100, Math.min(900, weight));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(base, safeWeight, false);
        }
        return Typeface.create(safeFamily,
                safeWeight >= 650 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private String toolHelp(String id) {
        switch (id) {
            case "move": return "اسحب لتحريك المشهد. قرّب بإصبعين أو عجلة الماوس.";
            case "select": return "تحديد مستطيل مع إضافة/طرح/تقاطع التحديد.";
            case "free_select": return "ارسم حول المنطقة؛ عند رفع إصبعك يُغلق التحديد تلقائياً.";
            case "fuzzy_select": return "المس نقطة لتحديد المنطقة المتصلة ذات اللون المتشابه.";
            case "color_select": return "المس الصورة لتحديد اللون الحالي في كامل الطبقة.";
            case "color_picker": return "المس الصورة لاختيار اللون من البكسل.";
            case "zoom": return "تكبير وتصغير مع الحفاظ على مركز المؤشر.";
            default: return "خيارات الأداة ستظهر هنا حسب الأداة النشطة.";
        }
    }

    private interface SliderCommit {
        void apply(int value);
    }

    private void addSlider(LinearLayout parent, String title,
                           int min, int max, int initial, SliderCommit commit) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(4), dp(8), dp(2));
        if (!desktopLayout) {
            card.setBackground(rounded(Color.rgb(35, 39, 45), 8));
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(title, desktopLayout ? 13 : 11, MUTED, false);
        TextView value = label(String.valueOf(initial), desktopLayout ? 13 : 11, TEXT, true);
        value.setGravity(Gravity.END);

        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(value, new LinearLayout.LayoutParams(dp(desktopLayout ? 60 : 42),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        card.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(desktopLayout ? 42 : 34)));

        LinearLayout.LayoutParams cardLp;
        if (desktopLayout) {
            cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            cardLp = new LinearLayout.LayoutParams(dp(150), dp(82));
            cardLp.setMargins(dp(3), 0, dp(3), 0);
        }
        parent.addView(card, cardLp);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int current = initial;
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                current = min + progress;
                value.setText(String.valueOf(current));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                if (commit != null && projectId != null) commit.apply(current);
            }
        });
    }

    private void addLiveSlider(LinearLayout parent, String title,
                               int min, int max, int initial, SliderCommit live) {
        LinearLayout card = sliderCard(title, String.valueOf(initial));
        TextView value = (TextView) ((LinearLayout) card.getChildAt(0)).getChildAt(1);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        seek.setKeyProgressIncrement(1);
        card.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(desktopLayout ? 42 : 34)));
        addSliderCard(parent, card);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int current = min + progress;
                value.setText(String.valueOf(current));
                if (fromUser && live != null) live.apply(current);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void addAdjustmentSlider(LinearLayout parent, String title, String operation,
                                     int initial, SliderCommit saveValue) {
        int safeInitial = Math.max(-100, Math.min(100, initial));
        LinearLayout card = sliderCard(title, signedValue(safeInitial));
        TextView value = (TextView) ((LinearLayout) card.getChildAt(0)).getChildAt(1);
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        value.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        value.setGravity(Gravity.END);

        SeekBar seek = new SeekBar(this);
        seek.setMax(200);
        seek.setProgress(safeInitial + 100);
        seek.setKeyProgressIncrement(1);
        card.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(desktopLayout ? 42 : 34)));
        addSliderCard(parent, card);

        final int[] current = {safeInitial};

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (!localEngine.hasImage()) return;
                activeAdjustmentOperation = operation;
                int requested = current[0];
                localExecutor.execute(() -> {
                    try {
                        int actual = localEngine.beginAdjustment(operation, requested);
                        if (actual != requested) {
                            runOnUiThread(() -> {
                                current[0] = actual;
                                seek.setProgress(actual + 100);
                                value.setText(signedValue(actual));
                                if (saveValue != null) saveValue.apply(actual);
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(shortText(e.getMessage())));
                    }
                });
            }

            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                current[0] = progress - 100;
                value.setText(signedValue(current[0]));
                if (saveValue != null) saveValue.apply(current[0]);
                if (fromUser && localEngine.hasImage()) {
                    requestAdjustmentPreview(operation, current[0], false);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (!localEngine.hasImage()) return;
                requestAdjustmentPreview(operation, current[0], true);
            }
        });
    }

    private LinearLayout sliderCard(String title, String initialText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(4), dp(8), dp(2));
        if (!desktopLayout) {
            card.setBackground(rounded(Color.rgb(35, 39, 45), 8));
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(title, desktopLayout ? 13 : 11, MUTED, false);
        TextView value = label(initialText, desktopLayout ? 13 : 11, TEXT, true);
        value.setGravity(Gravity.END);

        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(value, new LinearLayout.LayoutParams(dp(desktopLayout ? 60 : 48),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header);
        return card;
    }

    private void addSliderCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams cardLp;
        if (desktopLayout) {
            cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            cardLp = new LinearLayout.LayoutParams(dp(170), dp(82));
            cardLp.setMargins(dp(3), 0, dp(3), 0);
        }
        parent.addView(card, cardLp);
    }

    private String signedValue(int value) {
        if (value > 0) return "+" + value;
        return String.valueOf(value);
    }

    private void requestAdjustmentPreview(String operation, int value, boolean commit) {
        int token = adjustmentGeneration.incrementAndGet();

        localExecutor.execute(() -> {
            try {
                if (token != adjustmentGeneration.get()) return;

                Bitmap frame = localEngine.previewAdjustment(operation, value);
                if (token != adjustmentGeneration.get()) return;

                if (commit) {
                    frame = localEngine.commitAdjustment();
                }

                Bitmap result = frame;
                runOnUiThread(() -> {
                    if (token != adjustmentGeneration.get()) return;
                    if (canvas != null) canvas.setBitmapPreserveViewport(result);
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());

                    if (commit) {
                        activeAdjustmentOperation = null;
                        if (value != 0) addHistory(operation + " " + signedValue(value));
                        setStatus(operationLabel(operation) + " " + signedValue(value));
                        refreshRemotePanels();
                        flushPendingHistory();
                    } else {
                        setStatus(operationLabel(operation) + " " + signedValue(value));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    activeAdjustmentOperation = null;
                    setStatus("فشل التعديل: " + shortText(e.getMessage()));
                    toast(shortText(e.getMessage()));
                    flushPendingHistory();
                });
            }
        });
    }

    private String operationLabel(String operation) {
        if ("brightness".equals(operation)) return "السطوع";
        if ("contrast".equals(operation)) return "التباين";
        if ("saturation".equals(operation)) return "التشبع";
        return operation;
    }

    private void resetAdjustmentValues() {
        adjustmentGeneration.incrementAndGet();
        activeAdjustmentOperation = null;
        brightnessValue = 0;
        contrastValue = 0;
        saturationValue = 0;
    }

    private void refreshLayers() {
        if (layerList == null) return;
        layerList.removeAllViews();

        if (!localEngine.hasImage()) {
            if (!desktopLayout) addContextTitle(layerList, "الطبقات");
            else layerList.addView(label("الطبقات", 17, TEXT, true));
            layerList.addView(label("افتح صورة لعرض الطبقات", 13, MUTED, false));
            return;
        }

        if (!desktopLayout) {
            addContextTitle(layerList, "الطبقات");
        } else {
            layerList.addView(label("الطبقات", 17, TEXT, true));
        }

        layerList.addView(actionButton("+ طبقة", v ->
                applyRemote("add_layer", jsonOf("name",
                        "Layer " + (localEngine.layerInfo().size() + 1)))));
        layerList.addView(actionButton("+ صورة كطبقة", v -> chooseLayerImage()));
        layerList.addView(actionButton("نسخ", v ->
                applyRemote("duplicate_layer", new JSONObject())));
        layerList.addView(actionButton("تسمية", v ->
                showRenameActiveLayerDialog()));
        layerList.addView(actionButton("↑ للأعلى", v ->
                applyRemote("move_layer_up", new JSONObject())));
        layerList.addView(actionButton("↓ للأسفل", v ->
                applyRemote("move_layer_down", new JSONObject())));
        layerList.addView(actionButton("⤒ أعلى الكل", v ->
                applyRemote("move_layer_top", new JSONObject())));
        layerList.addView(actionButton("⤓ أسفل الكل", v ->
                applyRemote("move_layer_bottom", new JSONObject())));
        layerList.addView(actionButton("إظهار الكل", v ->
                applyRemote("show_all_layers", new JSONObject())));
        layerList.addView(actionButton("إخفاء البقية", v ->
                applyRemote("hide_other_layers", new JSONObject())));
        layerList.addView(actionButton("حذف", v ->
                applyRemote("delete_layer", new JSONObject())));
        layerList.addView(actionButton("دمج لأسفل", v ->
                applyRemote("merge_down", new JSONObject())));
        layerList.addView(actionButton("دمج المرئي", v ->
                applyRemote("merge_visible", new JSONObject())));
        layerList.addView(actionButton("Flatten الكل", v ->
                applyRemote("flatten", new JSONObject())));
        layerList.addView(actionButton("+ قناع", v ->
                applyRemote("add_layer_mask", new JSONObject())));
        layerList.addView(actionButton("عكس القناع", v ->
                applyRemote("invert_layer_mask", new JSONObject())));
        layerList.addView(actionButton("تطبيق القناع", v ->
                applyRemote("apply_layer_mask", new JSONObject())));
        layerList.addView(actionButton("حذف القناع", v ->
                applyRemote("remove_layer_mask", new JSONObject())));

        List<LocalEditorEngine.LayerInfo> layers = localEngine.layerInfo();
        LocalEditorEngine.LayerInfo active = null;

        for (LocalEditorEngine.LayerInfo info : layers) {
            if (info.active) active = info;

            if (!desktopLayout) {
                String text = (info.active ? "● " : "○ ") +
                        info.name + "\n" +
                        Math.round(info.opacity * 100f / 255f) + "% • " +
                        info.blendMode + (info.hasMask ? " • Mask" : "") +
                        (info.locked ? " • 🔒" : "");
                Button card = actionButton(text, v ->
                        applyRemote("set_active_layer", json("index", info.index)));
                if (info.active) card.setBackground(rounded(Color.rgb(44, 83, 145), 8));
                layerList.addView(card);

                Button eye = actionButton(info.visible ? "👁" : "⊘", v ->
                        applyRemote("toggle_layer_visibility", json("index", info.index)));
                layerList.addView(eye);
                Button lock = actionButton(info.locked ? "🔒" : "🔓", v ->
                        applyRemote("toggle_layer_lock", json("index", info.index)));
                layerList.addView(lock);
            } else {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(7), dp(5), dp(7), dp(5));
                row.setBackground(rounded(
                        info.active ? Color.rgb(44, 83, 145) : Color.rgb(44, 49, 57), 8));

                Button eye = flatButton(info.visible ? "◉" : "○", 14);
                eye.setOnClickListener(v ->
                        applyRemote("toggle_layer_visibility", json("index", info.index)));

                TextView name = label(info.name, 13, TEXT, info.active);
                name.setOnClickListener(v ->
                        applyRemote("set_active_layer", json("index", info.index)));

                TextView meta = label(
                        Math.round(info.opacity * 100f / 255f) + "% • " +
                                info.blendMode + (info.hasMask ? " • M" : "") +
                                (info.locked ? " • 🔒" : ""),
                        11, MUTED, false);
                meta.setGravity(Gravity.END);

                Button lock = flatButton(info.locked ? "🔒" : "🔓", 13);
                lock.setOnClickListener(v ->
                        applyRemote("toggle_layer_lock", json("index", info.index)));

                row.addView(eye, new LinearLayout.LayoutParams(dp(42), dp(36)));
                row.addView(lock, new LinearLayout.LayoutParams(dp(42), dp(36)));
                row.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1f));
                row.addView(meta, new LinearLayout.LayoutParams(dp(92), dp(36)));
                layerList.addView(row);
            }
        }

        if (active != null) {
            int initialOpacity = Math.round(active.opacity * 100f / 255f);
            int activeIndex = active.index;
            addSlider(layerList, "عتامة الطبقة", 0, 100, initialOpacity, value ->
                    applyRemote("set_layer_opacity", jsonOf(
                            "index", activeIndex,
                            "opacity", Math.round(value * 255f / 100f))));

            String activeBlend = active.blendMode;
            layerList.addView(actionButton("Blend: " + activeBlend, v ->
                    showChoice("Blend Mode", new String[]{
                            "Normal", "Multiply", "Screen", "Add", "Darken", "Lighten"
                    }, index -> {
                        String[] modes = {
                                "normal", "multiply", "screen", "add", "darken", "lighten"
                        };
                        if (index >= 0 && index < modes.length) {
                            applyRemote("set_layer_blend_mode", jsonOf(
                                    "index", activeIndex,
                                    "mode", modes[index]));
                        }
                    })));
        }
    }

    private void showRenameActiveLayerDialog() {
        List<LocalEditorEngine.LayerInfo> layers = localEngine.layerInfo();
        LocalEditorEngine.LayerInfo active = null;
        for (LocalEditorEngine.LayerInfo info : layers) {
            if (info.active) {
                active = info;
                break;
            }
        }
        if (active == null) return;

        EditText input = new EditText(this);
        input.setText(active.name);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setSelectAllOnFocus(true);

        int index = active.index;
        new AlertDialog.Builder(this)
                .setTitle("اسم الطبقة")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) value = "Layer " + (index + 1);
                    applyRemote("rename_layer", jsonOf("index", index, "name", value));
                })
                .show();
    }

    private void refreshChannels() {
        if (channelList == null) return;
        channelList.removeAllViews();

        if (!desktopLayout) addContextTitle(channelList, "القنوات");
        else channelList.addView(label("القنوات Channels", 17, TEXT, true));

        if (!localEngine.hasImage()) {
            channelList.addView(label("افتح صورة لعرض القنوات", 13, MUTED, false));
            return;
        }

        channelList.addView(actionButton("R", v ->
                applyRemote("channel_extract", json("channel", "R"))));
        channelList.addView(actionButton("G", v ->
                applyRemote("channel_extract", json("channel", "G"))));
        channelList.addView(actionButton("B", v ->
                applyRemote("channel_extract", json("channel", "B"))));
        channelList.addView(actionButton("Alpha", v ->
                applyRemote("channel_extract", json("channel", "A"))));

        TextView note = label(
                "استخراج القناة يحولها إلى صورة رمادية ويمكن التراجع عنه.",
                11, MUTED, false);
        note.setPadding(dp(8), 0, dp(8), 0);
        if (!desktopLayout) {
            channelList.addView(note, new LinearLayout.LayoutParams(dp(230), dp(72)));
        } else {
            channelList.addView(note);
        }
    }

    private void refreshPaths() {
        if (pathList == null) return;
        pathList.removeAllViews();

        if (!desktopLayout) addContextTitle(pathList, "المسارات");
        else pathList.addView(label("المسارات Paths", 17, TEXT, true));

        pathList.addView(actionButton("مسار حر", v ->
                showTool("free_select", "لاسو", null)));
        pathList.addView(actionButton("مسار مستطيل", v ->
                showTool("select", "تحديد", null)));
        pathList.addView(actionButton("تحديد نص", v -> {
            showTool("text", "نص", null);
            detectTextRegions();
        }));

        TextView note = label(
                "المسار الحر والمستطيل يعملان محليًا ويمكن تحويلهما إلى تحديد.",
                11, MUTED, false);
        note.setPadding(dp(8), 0, dp(8), 0);
        if (!desktopLayout) {
            pathList.addView(note, new LinearLayout.LayoutParams(dp(250), dp(72)));
        } else {
            pathList.addView(note);
        }
    }

    private void loadResources(String kind) {
        if (resourceList == null) return;
        resourceList.removeAllViews();

        if (!desktopLayout) {
            addContextTitle(resourceList, "الموارد");
            resourceList.addView(actionButton("الخطوط", v -> loadResources("fonts")));
            resourceList.addView(actionButton("الفرش", v -> loadResources("brushes")));
            resourceList.addView(actionButton("التدرجات", v -> loadResources("gradients")));
            resourceList.addView(actionButton("النقوش", v -> loadResources("patterns")));
            resourceList.addView(actionButton("الألوان", v -> loadResources("palettes")));

            String text;
            switch (kind) {
                case "fonts":
                    addFontPresetButtons(resourceList);
                    text = "اختر خطًا عربيًا وسيظهر مباشرة على النص غير المثبت";
                    break;
                case "brushes":
                    text = "فرش محلية";
                    break;
                case "gradients":
                    text = "تدرجات";
                    break;
                case "patterns":
                    addPatternButtons(resourceList);
                    text = "نقوش وزخارف هندسية محلية";
                    break;
                default:
                    text = "Palettes";
                    break;
            }
            addContextCard(resourceList, text);
            return;
        }

        resourceList.addView(label("الموارد المحلية", 17, TEXT, true));

        HorizontalScrollView picker = new HorizontalScrollView(this);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(actionButton("الخطوط", v -> loadResources("fonts")));
        buttons.addView(actionButton("الفرش", v -> loadResources("brushes")));
        buttons.addView(actionButton("التدرجات", v -> loadResources("gradients")));
        buttons.addView(actionButton("النقوش", v -> loadResources("patterns")));
        buttons.addView(actionButton("الألوان", v -> loadResources("palettes")));
        picker.addView(buttons);
        resourceList.addView(picker);

        String message;
        switch (kind) {
            case "fonts":
                addFontPresetButtons(resourceList);
                message = "معاينة واختيار خطوط عربية محلية للنص";
                break;
            case "brushes":
                message = "فرشاة • قلم • ممحاة تعمل محليًا الآن";
                break;
            case "gradients":
                message = "Linear Gradient يعمل محليًا الآن";
                break;
            case "patterns":
                addPatternButtons(resourceList);
                message = "النقوش والزخارف المحلية تطبق على الطبقة النشطة أو التحديد";
                break;
            default:
                message = "ألوان المقدمة والخلفية محلية بالكامل";
                break;
        }
        resourceList.addView(label(message, 12, TEXT, false));
    }

    private void addPatternButtons(LinearLayout parent) {
        parent.addView(actionButton("مربعات", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "checker",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 24))));
        parent.addView(actionButton("خطوط عمودية", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "stripes",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 24))));
        parent.addView(actionButton("خطوط مائلة", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "diagonal",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 28))));
        parent.addView(actionButton("شبكة متقاطعة", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "cross",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 28))));
        parent.addView(actionButton("معينات", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "diamond",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 32))));
        parent.addView(actionButton("نقاط", v ->
                applyRemote("pattern_fill", jsonOf(
                        "style", "dots",
                        "foreground", foregroundColor,
                        "background", "#000000",
                        "size", 24))));
    }

    private void addContextTitle(LinearLayout parent, String text) {
        TextView title = label(text, 13, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), 0, dp(8), 0);
        title.setBackground(rounded(Color.rgb(31, 34, 39), 8));
        parent.addView(title, new LinearLayout.LayoutParams(dp(92), dp(82)));
    }

    private void addContextCard(LinearLayout parent, String text) {
        TextView card = label(text, 11, TEXT, false);
        card.setGravity(Gravity.CENTER);
        card.setTextDirection(View.TEXT_DIRECTION_RTL);
        card.setPadding(dp(10), dp(4), dp(10), dp(4));
        card.setBackground(rounded(Color.rgb(35, 39, 45), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(72));
        lp.setMargins(dp(3), dp(5), dp(3), dp(5));
        parent.addView(card, lp);
    }

    private void refreshRemotePanels() {
        refreshLayers();
        refreshChannels();
        refreshPaths();
    }

    private void addHistory(String item) {
        history.add(0, item);
        if (history.size() > 30) history.remove(history.size() - 1);
        addHistoryRowsOnly();
    }

    private void addHistoryRowsOnly() {
        if (historyList == null) return;
        historyList.removeAllViews();

        TextView title = label("السجل", desktopLayout ? 17 : 13, TEXT, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        if (!desktopLayout) {
            title.setPadding(dp(10), 0, dp(10), 0);
            historyList.addView(title, new LinearLayout.LayoutParams(dp(90), dp(82)));
            int limit = Math.min(history.size(), 8);
            for (int i = 0; i < limit; i++) {
                TextView row = label("• " + history.get(i), 11, TEXT, false);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), 0, dp(10), 0);
                row.setBackground(rounded(Color.rgb(35, 39, 45), 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(72));
                lp.setMargins(dp(3), dp(5), dp(3), dp(5));
                historyList.addView(row, lp);
            }
            return;
        }

        historyList.addView(title);
        for (String h : history) {
            TextView row = label("• " + h, 13, TEXT, false);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            historyList.addView(row);
        }
    }

    private View buildStatusBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(desktopLayout ? 3 : 1), dp(10), dp(desktopLayout ? 3 : 1));
        row.setBackgroundColor(Color.rgb(16, 18, 21));

        statusText = label("جاهز", 12, MUTED, false);
        projectText = label("Offline • بدون صورة", 12, MUTED, false);
        zoomText = label("100%", 12, TEXT, true);
        zoomText.setGravity(Gravity.END);

        int statusHeight = desktopLayout ? 28 : 24;
        row.addView(statusText, new LinearLayout.LayoutParams(0, dp(statusHeight), 1.4f));
        row.addView(projectText, new LinearLayout.LayoutParams(0, dp(statusHeight), 1f));
        row.addView(zoomText, new LinearLayout.LayoutParams(dp(64), dp(statusHeight)));
        return row;
    }

    private void showServerDialog() {
        String imageInfo = localEngine.hasImage()
                ? localEngine.width() + "×" + localEngine.height() +
                  "  •  Undo " + localEngine.undoDepth() +
                  "  •  Redo " + localEngine.redoDepth()
                : "لا توجد صورة مفتوحة";

        new AlertDialog.Builder(this)
                .setTitle("UNB Pro Editor • Offline")
                .setMessage("المحرر يعمل داخل الهاتف بالكامل.\n\n" +
                        imageInfo + "\n\n" +
                        "لا يوجد سيرفر، لا رفع صور، ولا يحتاج إنترنت.")
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("معلومات المحرك", (d, w) ->
                        toast("LocalEditorEngine • Android Canvas/Bitmap"))
                .show();
    }

    private void chooseImage() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void chooseLayerImage() {
        if (busy) return;
        if (!localEngine.hasImage()) {
            toast("افتح الصورة الأساسية أولاً");
            return;
        }
        if (canvas != null && canvas.hasImageOverlay()) {
            new AlertDialog.Builder(this)
                    .setTitle("توجد صورة إضافية غير مثبتة")
                    .setMessage("ثبّت الصورة الحالية أو ألغِها قبل اختيار صورة أخرى.")
                    .setNegativeButton("إغلاق", null)
                    .setPositiveButton("تثبيت الحالية", (d, w) -> commitImageOverlay())
                    .show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_LAYER_IMAGE);
    }

    private void commitImageOverlay() {
        if (canvas == null || !canvas.hasImageOverlay()) {
            toast("أضف صورة ثانية أولاً");
            return;
        }
        if (busy) return;

        Bitmap overlay = canvas.getImageOverlayBitmap();
        if (overlay == null) return;

        final Bitmap source = overlay;
        final float x = canvas.getImageOverlayX();
        final float y = canvas.getImageOverlayY();
        final float scale = canvas.getImageOverlayScale();
        final float rotation = canvas.getImageOverlayRotation();
        final String layerName = pendingLayerImageName;

        setBusy(true);
        canvas.setInteractionMode("move");
        setStatus("جاري تثبيت الصورة كطبقة...");

        localExecutor.execute(() -> {
            try {
                Bitmap frame = localEngine.addImageLayer(
                        source, layerName, x, y, scale, rotation);
                runOnUiThread(() -> {
                    canvas.setBitmapPreserveViewport(frame);
                    canvas.clearImageOverlay();
                    canvas.clearSelectionOverlay();
                    canvas.clearDetectedTextRegions();
                    detectedTextRegions.clear();
                    selectedTextRegion = null;
                    recognizedText = "";
                    recognizedTextConfidence = 0;
                    pendingLayerImageName = "صورة إضافية";
                    setBusy(false);
                    setStatus("تمت إضافة الصورة كطبقة مستقلة");
                    addHistory("إضافة صورة كطبقة");
                    refreshRemotePanels();
                    openInspectorTab("layers");
                    refreshLayers();
                    showTool("move", "تحريك", null);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (canvas != null) canvas.setInteractionMode("image_place");
                    toast("تعذر إضافة الصورة: " + shortText(e.getMessage()));
                    setStatus("فشل تثبيت الصورة الإضافية");
                });
            }
        });
    }

    private Bitmap decodeBitmapRespectingExif(Uri uri, byte[] bytes) throws Exception {
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (decoded == null) return null;

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
            // Non-JPEG/PNG sources may not expose EXIF. Use decoded pixels as-is.
        }

        Matrix matrix = new Matrix();
        boolean transform = true;
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                transform = false;
                break;
        }

        if (!transform) return decoded;

        Bitmap corrected = Bitmap.createBitmap(
                decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (corrected != decoded && !decoded.isRecycled()) {
            decoded.recycle();
        }
        return corrected;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CREATE_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null &&
                    pendingExportBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new Exception("تعذر فتح ملف الحفظ");
                    out.write(pendingExportBytes);
                    out.flush();
                    toast("تم حفظ " + pendingExportName);
                    setStatus("تم حفظ الملف في الهاتف");
                } catch (Exception e) {
                    toast("فشل الحفظ: " + e.getMessage());
                }
            }
            pendingExportBytes = null;
            return;
        }

        if (requestCode == PICK_LAYER_IMAGE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri layerUri = data.getData();
            setBusy(true);
            setStatus("جاري تجهيز الصورة الإضافية...");

            localExecutor.execute(() -> {
                try {
                    byte[] bytes = readAll(layerUri);
                    String name = queryName(layerUri);
                    Bitmap decoded = decodeBitmapRespectingExif(layerUri, bytes);
                    if (decoded == null) throw new Exception("صيغة الصورة غير مدعومة");

                    runOnUiThread(() -> {
                        pendingLayerImageName =
                                name == null || name.trim().isEmpty() ? "صورة إضافية" : name;
                        canvas.startImageOverlay(decoded);
                        setBusy(false);
                        showTool("image_layer", "صورة إضافية", null);
                        setStatus("حرّك الصورة وكبّرها أو دوّرها ثم اضغط تثبيت الصورة كطبقة");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        toast("تعذر فتح الصورة الإضافية: " + shortText(e.getMessage()));
                        setStatus("فشل إضافة الصورة");
                    });
                }
            });
            return;
        }

        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;

        Uri uri = data.getData();
        setBusy(true);
        setStatus("جاري فتح الصورة محليًا...");

        localExecutor.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                String name = queryName(uri);
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (decoded == null) throw new Exception("صيغة الصورة غير مدعومة");

                localEngine.open(decoded);
                Bitmap frame = localEngine.current();

                runOnUiThread(() -> {
                    sourceBytes = bytes;
                    sourceName = name;
                    resetAdjustmentValues();
                    detectedTextRegions.clear();
                    selectedTextRegion = null;
                    if (canvas != null) {
                        canvas.clearDetectedTextRegions();
                        canvas.clearSelectionOverlay();
                    }
                    projectId = "LOCAL";
                    canvas.setBitmap(frame);
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    setBusy(false);
                    setStatus("المحرر المحلي جاهز — بدون سيرفر");
                    addHistory("فتح " + sourceName);
                    refreshRemotePanels();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast("تعذر فتح الصورة: " + e.getMessage());
                    setStatus("فشل فتح الصورة");
                });
            }
        });
    }

    private void applyRemote(String operation, JSONObject params) {
        if (!localEngine.hasImage()) {
            toast("افتح صورة أولاً");
            return;
        }
        if (busy) return;

        if (isTransformGeometryOperation(operation) &&
                canvas != null && canvas.hasTextOverlay()) {
            toast("ثبّت النص الحالي أو ألغِه قبل القص أو التدوير أو تغيير الحجم");
            setStatus("النص غير مثبت — ثبّته أو ألغِه قبل تغيير هندسة الصورة");
            return;
        }
        if (isTransformGeometryOperation(operation) &&
                canvas != null && canvas.hasImageOverlay()) {
            toast("ثبّت الصورة الإضافية أو ألغِها قبل تغيير هندسة الصورة الأساسية");
            setStatus("الصورة الإضافية غير مثبتة");
            return;
        }

        final int oldWidth = localEngine.width();
        final int oldHeight = localEngine.height();

        setBusy(true);
        setStatus("تنفيذ " + operation + " محليًا...");

        localExecutor.execute(() -> {
            try {
                Bitmap value = localEngine.apply(operation, params);
                Bitmap selectionOverlay = localEngine.selectionPreview();
                final boolean dimensionsChanged =
                        value.getWidth() != oldWidth || value.getHeight() != oldHeight;
                runOnUiThread(() -> {
                    if (canvas != null) {
                        if (dimensionsChanged) {
                            canvas.setBitmap(value);
                        } else {
                            canvas.setBitmapPreserveViewport(value);
                        }
                        if ("add_text".equals(operation)) {
                            canvas.clearTextOverlay();
                        }
                        canvas.clearStrokePreview();
                        canvas.setSelectionOverlay(selectionOverlay);
                        if (isTransformGeometryOperation(operation) &&
                                ("quad_transform".equals(operation) ||
                                 "transform".equals(activeTool) ||
                                 "perspective".equals(activeTool))) {
                            canvas.setInteractionMode("transform_quad");
                            canvas.resetTransformQuad();
                        }
                    }
                    if (!"brightness".equals(operation) &&
                            !"contrast".equals(operation) &&
                            !"saturation".equals(operation)) {
                        resetAdjustmentValues();
                        detectedTextRegions.clear();
                        selectedTextRegion = null;
                        if (canvas != null) canvas.clearDetectedTextRegions();
                    }
                    setBusy(false);
                    projectId = "LOCAL";
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    if ("color_match_selection".equals(operation)) {
                        setStatus("eraser".equals(eraseFillMode)
                                ? (eraserTransparent
                                    ? "تمت إزالة اللون المستهدف وترميم الخلفية داخل التحديد"
                                    : "تم استبدال اللون المستهدف داخل التحديد")
                                : "تمت تعبئة اللون المستهدف داخل التحديد");
                    } else {
                        setStatus("تم " + operation + " محليًا");
                    }
                    addHistory(operation);
                    refreshRemotePanels();
                    flushPendingHistory();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (canvas != null) canvas.clearStrokePreview();
                    setBusy(false);
                    setStatus("تعذر التنفيذ: " + shortText(e.getMessage()));
                    toast(shortText(e.getMessage()));
                    flushPendingHistory();
                });
            }
        });
    }

    private boolean isTransformGeometryOperation(String operation) {
        return "rotate".equals(operation) ||
                "flip_horizontal".equals(operation) ||
                "flip_vertical".equals(operation) ||
                "crop".equals(operation) ||
                "resize".equals(operation) ||
                "scale".equals(operation) ||
                "perspective".equals(operation) ||
                "quad_transform".equals(operation);
    }

    private void remoteHistory(String action) {
        if (!localEngine.hasImage()) return;

        if (canvas != null && canvas.hasTextOverlay()) {
            toast("ثبّت النص الحالي أو ألغِه قبل التراجع أو الإعادة");
            return;
        }
        if (canvas != null && canvas.hasImageOverlay()) {
            toast("ثبّت الصورة الإضافية أو ألغِها قبل التراجع أو الإعادة");
            return;
        }

        if (activeAdjustmentOperation != null) {
            pendingHistoryAction = action;
            setStatus(("redo".equals(action) ? "الإعادة" : "التراجع") +
                    " سيتم بعد تثبيت قيمة المؤشر");
            return;
        }

        if (busy) {
            pendingHistoryAction = action;
            setStatus(("redo".equals(action) ? "الإعادة" : "التراجع") +
                    " سيتم فور انتهاء العملية الحالية");
            return;
        }

        final int historyOldWidth = localEngine.width();
        final int historyOldHeight = localEngine.height();
        setBusy(true);
        setStatus("جاري " + action + " محليًا...");

        localExecutor.execute(() -> {
            try {
                Bitmap value = "redo".equals(action)
                        ? localEngine.redo()
                        : localEngine.undo();
                Bitmap selectionOverlay = localEngine.selectionPreview();
                final boolean historyDimensionsChanged =
                        value.getWidth() != historyOldWidth ||
                        value.getHeight() != historyOldHeight;

                runOnUiThread(() -> {
                    if (historyDimensionsChanged) {
                        canvas.setBitmap(value);
                    } else {
                        canvas.setBitmapPreserveViewport(value);
                    }
                    canvas.setSelectionOverlay(selectionOverlay);
                    resetAdjustmentValues();
                    setBusy(false);
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    setStatus("تم " + action + " محليًا");
                    addHistory(action);
                    refreshRemotePanels();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(shortText(e.getMessage()));
                    toast(shortText(e.getMessage()));
                });
            }
        });
    }

    private void flushPendingHistory() {
        if (busy || pendingHistoryAction == null) return;
        String action = pendingHistoryAction;
        pendingHistoryAction = null;
        remoteHistory(action);
    }

    private void exportProject(String format) {
        if (!localEngine.hasImage()) {
            toast("لا توجد صورة للحفظ");
            return;
        }
        if (busy) return;

        String fmt = format == null ? "png" : format.toLowerCase();
        if (!("png".equals(fmt) || "jpg".equals(fmt) ||
                "jpeg".equals(fmt) || "webp".equals(fmt))) {
            toast("الصيغ المدعومة حاليًا: PNG / JPG / WEBP");
            return;
        }

        setBusy(true);
        setStatus("جاري التصدير محليًا بصيغة " + fmt.toUpperCase() + "...");

        localExecutor.execute(() -> {
            try {
                byte[] value = localEngine.export(fmt, 95);
                runOnUiThread(() -> {
                    setBusy(false);
                    pendingExportBytes = value;
                    pendingExportName = "UNB_Pro_Editor_" + System.currentTimeMillis() + "." +
                            ("jpeg".equals(fmt) ? "jpg" : fmt);

                    String mime;
                    switch (fmt) {
                        case "jpg":
                        case "jpeg":
                            mime = "image/jpeg";
                            break;
                        case "webp":
                            mime = "image/webp";
                            break;
                        default:
                            mime = "image/png";
                    }

                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(mime);
                    save.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                    startActivityForResult(save, CREATE_EXPORT);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("فشل التصدير: " + shortText(e.getMessage()));
                    toast("فشل التصدير");
                });
            }
        });
    }

    private void pingServer() {
        setStatus("UNB Pro Editor • Offline 100%");
        if (projectText != null && !localEngine.hasImage()) {
            projectText.setText("Offline • بدون صورة");
        }
    }

    private void showMenuGroup(String group) {
        switch (group) {
            case "ملف":
                showChoice("ملف", new String[]{
                        "فتح صورة", "إضافة صورة كطبقة", "حفظ PNG", "حفظ JPG", "حفظ WEBP"
                }, index -> {
                    if (index == 0) chooseImage();
                    else if (index == 1) chooseLayerImage();
                    else if (index == 2) exportProject("png");
                    else if (index == 3) exportProject("jpg");
                    else if (index == 4) exportProject("webp");
                });
                break;

            case "تعديل":
                showChoice("تعديل", new String[]{"تراجع", "إعادة"}, index ->
                        remoteHistory(index == 0 ? "undo" : "redo"));
                break;

            case "تحديد":
                showChoice("تحديد", new String[]{
                        "تحديد الكل", "إلغاء التحديد", "عكس التحديد",
                        "توسيع 5px", "تقليص 5px", "Feather 5px",
                        "حدود 2px", "Sharpen"
                }, index -> {
                    switch (index) {
                        case 0: applyRemote("select_all", new JSONObject()); break;
                        case 1: applyRemote("select_none", new JSONObject()); break;
                        case 2: applyRemote("invert_selection", new JSONObject()); break;
                        case 3: applyRemote("grow", json("steps", 5)); break;
                        case 4: applyRemote("shrink", json("steps", 5)); break;
                        case 5: applyRemote("feather", json("radius", 5)); break;
                        case 6: applyRemote("border", json("radius", 2)); break;
                        case 7: applyRemote("sharpen_selection", new JSONObject()); break;
                    }
                });
                break;

            case "عرض":
                showChoice("عرض", new String[]{"ملاءمة للصورة", "100%", "تكبير 125%", "تصغير 80%"},
                        index -> {
                            if (canvas == null) return;
                            if (index == 0) canvas.fitToView();
                            else if (index == 1) canvas.setZoom(1f);
                            else if (index == 2) canvas.setZoom(canvas.getZoom() * 1.25f);
                            else canvas.setZoom(canvas.getZoom() * 0.8f);
                        });
                break;

            case "صورة":
                showChoice("صورة", new String[]{
                        "تدوير 90° يمين", "تدوير 90° يسار", "تدوير 180°",
                        "عكس أفقي", "عكس عمودي", "تغيير الحجم"
                }, index -> {
                    if (index == 0) applyRemote("rotate", json("degrees", 90));
                    else if (index == 1) applyRemote("rotate", json("degrees", -90));
                    else if (index == 2) applyRemote("rotate", json("degrees", 180));
                    else if (index == 3) applyRemote("flip_horizontal", new JSONObject());
                    else if (index == 4) applyRemote("flip_vertical", new JSONObject());
                    else showResizeDialog();
                });
                break;

            case "طبقة":
                showChoice("طبقة", new String[]{
                        "فتح لوحة الطبقات", "إضافة صورة كطبقة", "طبقة جديدة", "نسخ الطبقة",
                        "حذف الطبقة", "دمج لأسفل", "Flatten"
                }, index -> {
                    if (index == 0) {
                        openInspectorTab("layers");
                        refreshLayers();
                    } else if (index == 1) {
                        chooseLayerImage();
                    } else if (index == 2) {
                        applyRemote("add_layer", jsonOf("name", "Layer"));
                    } else if (index == 3) {
                        applyRemote("duplicate_layer", new JSONObject());
                    } else if (index == 4) {
                        applyRemote("delete_layer", new JSONObject());
                    } else if (index == 5) {
                        applyRemote("merge_down", new JSONObject());
                    } else {
                        applyRemote("flatten", new JSONObject());
                    }
                });
                break;

            case "ألوان":
                showChoice("ألوان", new String[]{
                        "السطوع", "التباين", "التشبع",
                        "Levels", "Curves", "Threshold", "Posterize",
                        "Desaturate", "Invert", "Equalize", "Color Balance"
                }, index -> {
                    if (index == 0) {
                        showTool("brightness_adjust", "سطوع", null);
                    } else if (index == 1) {
                        showTool("contrast_adjust", "تباين", null);
                    } else if (index == 2) {
                        showTool("saturation_adjust", "تشبع", null);
                    } else if (index == 3) {
                        applyRemote("levels", new JSONObject());
                    } else if (index == 4) {
                        applyRemote("curves", new JSONObject());
                    } else if (index == 5) {
                        showNumericOperationDialog("Threshold 0-100", "threshold", "low", 50, 0, 100, true);
                    } else if (index == 6) {
                        showNumericOperationDialog("Posterize 2-256", "posterize", "levels", 4, 2, 256, false);
                    } else if (index == 7) {
                        applyRemote("desaturate", new JSONObject());
                    } else if (index == 8) {
                        applyRemote("invert", new JSONObject());
                    } else if (index == 9) {
                        applyRemote("equalize", new JSONObject());
                    } else {
                        applyRemote("color_balance", new JSONObject());
                    }
                });
                break;

            case "أدوات":
                showChoice("أدوات", new String[]{
                        "تحريك", "تحديد", "لاسو", "تحديد سحري", "حسب اللون",
                        "تحويل حر", "منظور",
                        "فرشاة", "قلم", "ممحاة/تعبئة", "تدرج", "نص",
                        "Clone", "Heal", "Smudge", "Dodge/Burn", "Color Picker"
                }, index -> {
                    String[] ids = {
                            "move", "select", "free_select", "fuzzy_select", "color_select",
                            "transform", "perspective",
                            "brush", "pencil", "erase_fill", "gradient", "text",
                            "clone", "heal", "smudge", "dodge_burn", "color_picker"
                    };
                    String[] names = {
                            "تحريك", "تحديد", "لاسو", "سحري", "حسب لون",
                            "تحويل حر", "منظور",
                            "فرشاة", "قلم", "ممحاة/تعبئة", "تدرج", "نص",
                            "استنساخ", "ترميم", "تلطيخ", "إضاءة", "لون"
                    };
                    if (index >= 0 && index < ids.length) showTool(ids[index], names[index], null);
                });
                break;

            case "فلاتر":
                showChoice("فلاتر محلية", new String[]{
                        "Gaussian Blur", "Unsharp Mask", "Noise Reduction",
                        "Bloom", "Emboss", "Edge", "Oilify", "Pixelize",
                        "Mosaic", "Motion Blur", "Color Temperature"
                }, index -> {
                    switch (index) {
                        case 0:
                            showNumericOperationDialog("Gaussian Blur • Radius 1-30",
                                    "gaussian_blur", "radius", 4, 1, 30, false);
                            break;
                        case 1:
                            showNumericOperationDialog("Unsharp • Amount 0-300%",
                                    "unsharp_mask", "amount", 115, 0, 300, true);
                            break;
                        case 2:
                            showNumericOperationDialog("Noise Reduction • Radius 1-10",
                                    "noise_reduction", "radius", 1, 1, 10, false);
                            break;
                        case 3:
                            showNumericOperationDialog("Bloom • Strength 0-100%",
                                    "bloom", "strength", 35, 0, 100, true);
                            break;
                        case 4:
                            applyRemote("emboss", new JSONObject());
                            break;
                        case 5:
                            applyRemote("edge", new JSONObject());
                            break;
                        case 6:
                            applyRemote("oilify", new JSONObject());
                            break;
                        case 7:
                            showNumericOperationDialog("Pixelize • Size 2-100",
                                    "pixelize", "size", 12, 2, 100, false);
                            break;
                        case 8:
                            showNumericOperationDialog("Mosaic • Size 2-100",
                                    "mosaic", "size", 18, 2, 100, false);
                            break;
                        case 9:
                            showNumericOperationDialog("Motion Blur • Radius 2-50",
                                    "motion_blur", "radius", 9, 2, 50, false);
                            break;
                        case 10:
                            showNumericOperationDialog("Color Temperature -100 إلى +100",
                                    "color_temperature", "value", 20, -100, 100, false);
                            break;
                    }
                });
                break;

            case "نوافذ":
                showChoice("نوافذ", new String[]{
                        "خصائص", "طبقات", "قنوات", "مسارات", "موارد", "السجل"
                }, index -> {
                    String[] tabs = {"properties", "layers", "channels", "paths", "resources", "history"};
                    if (index < 0 || index >= tabs.length) return;
                    openInspectorTab(tabs[index]);
                    if (index == 1) refreshLayers();
                    else if (index == 2) refreshChannels();
                    else if (index == 3) refreshPaths();
                    else if (index == 4) loadResources("fonts");
                });
                break;

            default:
                toast("القائمة غير متاحة");
        }
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private void showChoice(String title, String[] items, ChoiceHandler handler) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    if (handler != null) handler.onChoice(which);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showPerspectiveDialog() {
        if (!localEngine.hasImage()) {
            toast("افتح صورة أولاً");
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText top = new EditText(this);
        top.setHint("تقارب الحافة العليا %");
        top.setText("8");
        top.setTextColor(TEXT);
        top.setHintTextColor(MUTED);
        top.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(top);

        EditText bottom = new EditText(this);
        bottom.setHint("تقارب الحافة السفلى %");
        bottom.setText("0");
        bottom.setTextColor(TEXT);
        bottom.setHintTextColor(MUTED);
        bottom.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(bottom);

        new AlertDialog.Builder(this)
                .setTitle("منظور Perspective")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    try {
                        double topPercent = Double.parseDouble(top.getText().toString());
                        double bottomPercent = Double.parseDouble(bottom.getText().toString());
                        double width = localEngine.width();
                        applyRemote("perspective", jsonOf(
                                "top_inset", width * topPercent / 100.0,
                                "bottom_inset", width * bottomPercent / 100.0
                        ));
                    } catch (Exception e) {
                        toast("أدخل نسباً صحيحة");
                    }
                })
                .show();
    }

    private void showResizeDialog() {
        if (projectId == null) {
            toast("افتح صورة أولاً");
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText width = new EditText(this);
        width.setHint("العرض px");
        width.setInputType(InputType.TYPE_CLASS_NUMBER);
        width.setTextColor(TEXT);
        width.setHintTextColor(MUTED);

        EditText height = new EditText(this);
        height.setHint("الارتفاع px");
        height.setInputType(InputType.TYPE_CLASS_NUMBER);
        height.setTextColor(TEXT);
        height.setHintTextColor(MUTED);

        if (canvas != null && canvas.getBitmap() != null) {
            width.setText(String.valueOf(canvas.getBitmap().getWidth()));
            height.setText(String.valueOf(canvas.getBitmap().getHeight()));
        }

        box.addView(width);
        box.addView(height);

        new AlertDialog.Builder(this)
                .setTitle("تغيير حجم الصورة")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    try {
                        int w = Integer.parseInt(width.getText().toString());
                        int h = Integer.parseInt(height.getText().toString());
                        applyRemote("resize", jsonOf("width", w, "height", h));
                    } catch (Exception e) {
                        toast("أدخل عرضاً وارتفاعاً صحيحين");
                    }
                })
                .show();
    }

    private void showNumericOperationDialog(String title, String operation, String key,
                                            int initial, int min, int max, boolean normalize100) {
        EditText value = new EditText(this);
        value.setText(String.valueOf(initial));
        value.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL |
                InputType.TYPE_NUMBER_FLAG_SIGNED);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(value)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    try {
                        double v = Double.parseDouble(value.getText().toString());
                        v = Math.max(min, Math.min(max, v));
                        if (normalize100) v /= 100.0;
                        applyRemote(operation, json(key, v));
                    } catch (Exception e) {
                        toast("قيمة غير صحيحة");
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        localExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_O:
                    chooseImage();
                    return true;
                case KeyEvent.KEYCODE_S:
                    exportProject("png");
                    return true;
                case KeyEvent.KEYCODE_Z:
                    remoteHistory(event.isShiftPressed() ? "redo" : "undo");
                    return true;
                case KeyEvent.KEYCODE_0:
                    if (canvas != null) canvas.fitToView();
                    return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && canvas != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_PLUS ||
                    event.getKeyCode() == KeyEvent.KEYCODE_EQUALS) {
                canvas.setZoom(canvas.getZoom() * 1.2f);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MINUS) {
                canvas.setZoom(canvas.getZoom() / 1.2f);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isDesktopLayout() {
        Configuration c = getResources().getConfiguration();
        return c.smallestScreenWidthDp >= 600 ||
                c.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void setBusy(boolean value) {
        busy = value;
        if (canvas != null) {
            canvas.setTextOverlayEditingEnabled(!value);
            if (!value && canvas.hasTextOverlay()) {
                updateTextOverlayStyle();
            }
        }
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String activeToolLabel() {
        return toolTitle == null ? activeTool : toolTitle.getText().toString();
    }

    private JSONObject json(String key, Object value) {
        JSONObject o = new JSONObject();
        try { o.put(key, value); } catch (Exception ignored) {}
        return o;
    }

    private JSONObject jsonOf(Object... pairs) {
        JSONObject o = new JSONObject();
        if (pairs == null) return o;
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception ignored) {}
        return o;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("تعذر قراءة الملف");
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        String name = "image.png";
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        return name == null ? "image.png" : name;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button b = flatButton(text, desktopLayout ? 12 : 11);
        b.setBackground(rounded(PANEL_2, 8));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(desktopLayout ? 40 : 36));
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private Button flatButton(String text, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setPadding(dp(desktopLayout ? 11 : 8), 0, dp(desktopLayout ? 11 : 8), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private Button tabButton(String text) {
        Button b = flatButton(text, 12);
        b.setBackground(rounded(PANEL_2, 8));
        return b;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private View dividerVertical() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private View verticalDivider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(28));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String shortText(String s) {
        if (s == null) return "خطأ";
        s = s.replace("\n", " ").trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
